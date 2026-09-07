# Senior Dev Audit: skillars-deferred-99

**Reviewed:** 2026-09-07 | **Scope:** 17 ACs across payment, reliability, i18n, database, and video  
**Finding Level:** Medium — 11 issues raised.

---

## ⚠️ Correction pass (2026-09-07, verified against HEAD source)

The original audit's claim of "no false positives detected" **did not hold** once each finding was checked against the actual code at `master@69a1ca8`. Corrections:

**False positives (no story change made):**

- **AC2 "Budget Math Contradiction" (§AC2 Issue 1)** — WRONG. The story says the *added* forced-termination wait must fit "the ~7 s headroom". That headroom is `stop_grace_period (55 s) − existing ~48 s budget = ~7 s`; `FORCED_TERMINATION_SECONDS = 1 s` × 6 new pools = 6 s ≤ 7 s. The audit conflated the existing per-pool `awaitTermination` budget with the new increment. `ExecutorShutdown.java`'s own javadoc carries the 48 s / 55 s arithmetic and the story references it correctly.
- **AC2 "grep missed `storageUploadExecutor`" (§AC2 Additional gap)** — WRONG. The story overview says "1 of 7 executor pools" and AC2's 6 named pools are exactly the 6 `ThreadPoolTaskExecutor` pools; `storageUploadExecutor` (raw `ThreadPoolExecutor`) is explicitly named as the **already-covered** 7th (`gracefulFixedPool`). Nothing was missed. `ExecutorShutdown.java` javadoc itself says "seven pools, not the five the story enumerated".
- **AC6 "`neutral` outcome not caught" (§AC6 Issue 1)** — WRONG / fabricated. GitHub Actions `steps.*.outcome` is only ever `success` / `failure` / `cancelled` / `skipped`. There is no `neutral` step outcome (`neutral` is a Checks-API *conclusion*, not exposed here). `outcome != 'success'` is complete.
- **AC7 "`\bplayer_id\b` still matches inside `player_id_new`" (§AC7 Issue 2)** — WRONG. `_` is a `\w` character, so there is no `\b` between `id` and `_new`; `\bplayer_id\b` does **not** match `player_id_new`. `MigrationLint.wordBoundary()` (already at HEAD) is `Pattern.compile("\\b" + quote(token) + "\\b")` and behaves correctly. The suggested regression test is still worth adding (folded into AC7) but the stated failure mode is not real.

**Audit misses (story updated):**

- **AC6 is already ~90% implemented at HEAD.** `deferred-96`'s commit `542eab1` (in `master`, ancestor of this branch) added the `set +e` + `result=error` fallback, `timeout-minutes: 15`, ssh `-o ConnectTimeout=10 -o BatchMode=yes`, and `steps.smoke.outcome == 'failure' || 'cancelled'` guards on revert / notify / fail-marker. The story's AC6 "Verified at HEAD" text described the *pre-`542eab1`* tree and was stale. AC6 has been narrowed to verification + one genuine residual (a passing smoke with an unwritable `$GITHUB_OUTPUT` silently no-ops) + ledger close. The audit spent AC6 on the fabricated `neutral` case instead of catching this.
- **AC7 word-boundary half already landed** — verifiable now, not "work to do during dev".

**Legitimate findings (folded into the story):** AC1 (idempotency + reconciliation-write-failure), AC3 (dynamic-key limitation + bounded WARN cache), AC5 (concrete timeout formula + TLS trust policy), AC9 (explicit NULL-row policy + job-failure metric — but the CTID→PK point is weak: the ctid batch-delete-in-one-statement is the documented house pattern and is snapshot-consistent), AC11 (prefer pre-check; ERROR-log real overflow; note the 3-term sum), AC13 (bound the `await`s), AC14 (unmount guard + toast de-dupe), **AC16 (market-resolution precedence — the one substantive gap)**.

**Legitimate but low-value / already-covered:** AC5 per-provider override (audit marked optional), AC10 global-vs-per-site budget (AC already implies global), AC12 UTC TODO comment, AC15 Grafana alert (AC already says out of scope), AC4 fixture-state check.

The original findings below are retained for the record; read them through this correction pass.

---

## Summary

The story is well-scoped and targets genuine, latent bugs across the platform. The narrative is precise, and the problem statements are re-verified. However, several ACs carry **underspecified details, contradictory constraints, or missed edge cases** that will require clarification or defensive implementation during dev.

**No blockers found.** Issues surface as:
- **Contradictions:** AC2 (executor escalation budget math vs. stated headroom)
- **Underspecified:** AC5 (timeout formula), AC6 (outcome coverage), AC11 (overflow handling semantics)
- **Memory/robustness gaps:** AC3 (WARN-once logging), AC9 (NULL handling), AC13 (test timeout)
- **Edge cases:** AC14 (navigation-away), AC16 (locale-to-market), AC7 (non-ASCII identifiers)

---

## Issues by AC

### AC1: `purchasePack` Compensating Refund — Payment Atomicity Gap

**Issue:** Idempotency + double-refund risk on retry.

If `purchasePack()` fails after the refund attempt (e.g., a transient DB failure, a timeout writing the reconciliation record, or a network blip on the outer throw), the caller may retry — but there's **no guard against re-issuing the refund**. The refund is an external side-effect on Stripe; no stored flag marks it as "already done."

**Current code (line ~96):** `paymentGateway.refund(...)` is called once per `purchasePack` invocation with no idempotency key or record check.

**Recommendation:**
- Before calling `refund()`, check if a reconciliation record for this `paymentIntentId` already exists. Skip the refund if it does.
- If the `refund()` succeeds, record the success state in the reconciliation record so a replay is a no-op.
- Alternatively, generate a Stripe **idempotency key** unique to this attempt (e.g., `sha256(paymentIntentId + attemptNumber)`) and pass it to the gateway; Stripe will replay the same response on retry.

**Also missing:** What happens if **persisting the reconciliation record itself fails**? If the refund succeeds but the write to the reconciliation table times out, the refund is silently dropped from the database (the whole reason the AC exists), and the next invocation will retry the refund. This compounds the double-refund risk.

**Clarify during dev:** Which idempotency mechanism will you use? Record-check, Stripe idempotency key, or an outbox pattern? And does the deferred-91 outbox mentioned in the fix approach already handle refund records, or is this a new table?

---

### AC2: Executor Forced-Termination — Budget Math Contradiction

**Issue:** AC states "size each pool's slice so the sum ≤ ~7 s" headroom, but `ExecutorShutdown.java` javadoc (lines 76–86) budgets **~48 s total** across all pools, fitting within the 55 s `stop_grace_period`.

**Current math in ExecutorShutdown.java:**
```
OUTBOX_DRAIN_SECONDS + SLU_RETRY_SECONDS + STORAGE_UPLOAD_SECONDS
+ SEND_MAIL_SECONDS + MODERATION_SECONDS + SHARED_ASYNC_SECONDS + REPORT_SECONDS
= ~48 s (worst case, sequential destruction)
```

**AC2 says:** "Total added worst-case wait must stay inside the ~7 s headroom…"

These are **contradictory.** If the existing javadoc budget is correct, the 7 s is already exhausted. If 7 s is the real headroom for *new* work, the ExecutorShutdown javadoc budget needs revision.

**Additional gap:** The AC lists 6 pools; the ExecutorShutdown javadoc says **7 pools exist** (lists all of them). `storageUploadExecutor` is a raw `ThreadPoolExecutor`, not a `ThreadPoolTaskExecutor`, which the AC's grep-based discovery missed.

**Recommendation:**
- Verify the actual values of `OUTBOX_DRAIN_SECONDS`, `SLU_RETRY_SECONDS`, etc. in the current code (not shown in the story).
- If the 7 s is a new headroom added *after* the existing budget, clarify whether the existing pool slices were reduced to accommodate the new ~7 s policy.
- If the 48 s budget is correct, restate the AC goal as "apply the escalation pattern to all 7 pools, respecting the established `stop_grace_period` budget (currently ~48 s)."
- Include the raw `ThreadPoolExecutor` (`storageUploadExecutor`) in the scope.

---

### AC3: MessageSource Robustness — Dynamic Keys & Memory Leaks

**Issue:** Build-time completeness test scope + WARN-once logging implementation.

The fix approach says "scan `src/main` for message-code string literals" — but not all message codes are literals:

```java
// Caught by grep:
messageSource.getMessage("key.constant", ...)

// Missed by grep (dynamic construction):
messageSource.getMessage(prefix + someVariable, ...)
messageSource.getMessage("key." + locale.getLanguage(), ...)
```

The test will pass but production code using dynamic keys can still hit missing-base-bundle 500s.

**Also:** "log WARN once per missing code" — how is "once" implemented? If you use a `Set<String>` to track warned keys, it grows unbounded if a production bug causes dynamic missing keys. A `WeakHashMap` or caffeine cache with a time-based eviction would be safer, but the AC doesn't specify.

**Recommendation:**
- For the completeness test, scope it to **string literals only** (grep `"[a-z.]+"` passed to `.getMessage`). Document the limitation: "Does not catch dynamically-constructed keys."
- For the WARN-once logging, use a bounded cache (e.g., Caffeine `maximumSize(1000)` with 10-minute expiry) to prevent unbounded growth.
- Consider whether dynamic keys are acceptable in your codebase, or if they should be flagged as a lint warning.

---

### AC4: OTP-Resend 409 IT — No Issues Found ✓

The AC is well-defined: mirror the existing fixture pattern and assert the HTTP status + error key. Low risk, contingent only on verifying the fixture is a clean starting point (no existing active OTP records that would interfere).

**Recommendation:** Verify `secondActiveOtpInsert_…` fixture assumes a null/empty OTP state before insertion, or creates the OTP explicitly.

---

### AC5: SmtpHealthIndicator — Timeout Formula & Certificate Validation Gaps

**Issue 1: Timeout math underspecified.**

The fix approach says "overall budget ≈ `max(perProviderTimeout) + slack`" — what is `slack`? Is it 1 s, 100 ms, or adaptive?

If there are 3 providers with 5 s, 7 s, and 6 s timeouts, is the overall budget 7 + slack? What if one provider is pathologically slow?

**Recommendation:** Specify the formula explicitly in code or config: e.g., "overall timeout = max(per-provider timeout) + 1 s for overhead."

**Issue 2: Port 465 (implicit TLS) certificate validation.**

The fix approach says "open an `SSLSocket` and treat a completed handshake as UP" — but what certificate validation strategy is used?

- **Strict (production):** Validate against the provider's CA chain. Self-signed certs fail.
- **Loose (development/test):** Trust any cert.

The story doesn't specify. In test environments with self-signed certs, the health check would always report DOWN.

**Recommendation:** Use `SSLContext.getInstance("TLS")` with a custom `TrustManager` for test; document whether the production code should have a strict or permissive trust policy.

**Issue 3: No per-provider timeout override.**

If one SMTP provider is notoriously slow, there's no way to give it a longer budget without raising the global timeout for all. This could degrade the overall probe latency.

**Recommendation (optional):** Add a per-provider timeout override in config: `app.notification.smtp-providers.<name>.timeout-ms`.

---

### AC6: Deploy Smoke-Step Error Visibility — Incomplete Outcome Coverage

**Issue:** The AC guards the downstream steps on `steps.smoke.outcome != 'success'` — but GitHub Actions has multiple outcomes:

- `success` → step succeeded
- `failure` → step exited with non-zero code
- `skipped` → step was skipped
- `cancelled` → step was cancelled
- `neutral` → step reported success but some conditional was not met (rare)

The condition `outcome != 'success'` catches `failure`, `skipped`, `cancelled`, but **not `neutral`.** Is `neutral` supposed to trigger revert/notification? The AC doesn't address it.

**Also:** The smoke step is guarded by `set +e; <command>; echo "result=..." >> "$GITHUB_OUTPUT"` to ensure `result` is always written — but what if the `echo` command itself fails (e.g., `$GITHUB_OUTPUT` write permission denied, disk full)? The outer `echo` cannot be inside the `set +e` block without also silencing the status capture.

**Recommendation:**
- Explicitly enumerate the outcomes that should trigger revert/notification. Assume `neutral` is not one unless you have evidence otherwise.
- Ensure the `echo "result=..."` write is bulletproof: e.g., `echo "result=fail" >> "$GITHUB_OUTPUT" || (echo "ERROR: output write failed" >&2; exit 1)`.

---

### AC7: MigrationLint Caching — Precondition Verification Required

**Issue 1: Word-boundary patch verification.**

The AC says "verify whether [the word-boundary patch] landed; if it did … this AC reduces to the caching half + a regression test." But this is presented as work to do during dev, not as a verified precondition.

**Verify before dev starts:** Does the current HEAD code already use word-boundary matching, or is it still a bare substring match?

**Issue 2: Non-ASCII identifier handling.**

The fix uses `\b`-anchored regex matching for word boundaries — but `\b` doesn't work reliably for non-ASCII characters or underscores. In regex:
- `\b` is a transition between `\w` and `\W`.
- `\w` = `[a-zA-Z0-9_]`, so underscores are treated as word characters.
- A dropped identifier like `player_id` will still match inside `player_id_new` as a "substring with word boundary at the underscore."

If your dropped identifiers include underscores (common in Java/SQL), test carefully.

**Recommendation:**
- Confirm the word-boundary patch is already applied; make it a verification task in the story's opening, not implementation work.
- Add a regression test for an underscore-heavy dropped name (e.g., `player_session_id` in a file that also contains `player_session_id_new`).

---

### AC8: Migration Conventions Debt — No Issues Found ✓

Pure documentation work. The approach (append-only, don't edit applied migrations) is correct.

---

### AC9: SLU Marker Retention — NULL Handling & Concurrency Gaps

**Issue 1: NULL handling for timestamp.**

The AC says "if V119 has no timestamp column, add one nullable via a new additive migration and backfill lazily (new rows stamped; NULL treated as 'keep')."

The pruning DELETE will be `DELETE FROM player_slu_weekly_snapshot_applied WHERE created_at < now() - interval '90 days'` — but if `created_at` is NULL for old rows, the condition is NULL, and those rows are kept (correct). However, the story doesn't specify whether the lazy backfill strategy (leaving old rows NULL forever) is acceptable. If old rows live for months without a timestamp, they won't be pruned even after 90 days.

**Recommendation:** 
- Either backfill all NULL `created_at` to a known past date in the migration (e.g., deployment date), or
- Use `WHERE created_at IS NULL OR created_at < now() - interval '90 days'` to include unpruned tombstones, or
- State explicitly that NULL rows are archived and will never be pruned (accept the policy).

**Issue 2: CTID-based DELETE & concurrency.**

The proposed `DELETE … WHERE ctid IN (SELECT ctid … LIMIT :batch)` pattern is clever, but CTID is a Postgres implementation detail and can change mid-transaction under concurrent updates. On high-traffic tables, this could accidentally delete "wrong" rows if a concurrent UPDATE moves a row.

**Recommendation:** Use the primary key (session UUID + player ID + week) instead: `DELETE FROM player_slu_weekly_snapshot_applied WHERE (session_id, player_id, week) IN (SELECT session_id, player_id, week FROM ... WHERE created_at < ... LIMIT :batch)`. It's safer and indexes better.

**Issue 3: No alerting on job failure.**

The AC specifies a `@Scheduled` job but doesn't mention what happens if it fails. Does it log an error? Does `@SchedulerLock` suppress errors? If the job is silently failing, the table will grow unbounded.

**Recommendation:** Ensure the job is wrapped in a try-catch that logs a WARN/ERROR on failure, and consider adding a metric (`slu_marker_prune_failures`) for alerting.

---

### AC10: PessimisticLockRetryer — Instrumentation Scope Clear ✓

The AC correctly treats this as a documented tradeoff (connection-hold, savepoint limitation). Externalizing config, adding metrics, and expanding javadoc is the right scope.

**One clarification:** Should the retry budget (e.g., `3.2 s`) be tunable **per-call-site** (e.g., `findByIdForUpdate` might need different patience than another operation), or global? The AC doesn't specify. Assume **global** unless the code reveals a need for per-site tuning.

---

### AC11: Quota Overflow — Semantics of ArithmeticException Handling

**Issue:** The AC says "treat `ArithmeticException` as 'request exceeds quota' (return the same `QUOTA_EXCEEDED`-class rejection)."

This is **misleading to the client.** An overflow is not a quota overage; it's a bug (numbers should never overflow at practical quota sizes). Silently treating it as quota-exceeded hides the bug.

**Example:**
- User has 8 EB of storage.
- Requests 8 EB more.
- `8EB + 8EB` overflows `long` and wraps to a negative number.
- The check `used + requested > limit` compares `-8EB > limit`, which is true, so the request is rejected as over-quota. Correct result, but for the wrong reason.

**Recommendation:**
- Use a **pre-check** instead: `if (requestedBytes > limit - usedBytes) return QUOTA_EXCEEDED`, which avoids overflow entirely and is clearer.
- If you use `Math.addExact`, log an ERROR (not a silent rejection) — this is a bug in quota tracking or config, not a normal user operation.

---

### AC12: Bandwidth Period Anchor — Timezone Assumption

**Issue:** The fix uses `YearMonth.now(clock).atDay(1).atStartOfDay(ZoneOffset.UTC)` — hardcoded UTC.

If the app eventually needs to support multi-market or per-user timezones, this becomes a problem. A user's bandwidth period should reset at their local midnight, not UTC.

**Recommendation:** If this is a single-timezone (UTC) app today, add a comment: `// TODO: make configurable per-market if multi-timezone support is needed`. If multi-market support is planned, use `app.timezone` or per-user preference instead.

---

### AC13: De-flake Concurrency IT — Brittle DB Dependency & Test Timeout

**Issue 1: PostgreSQL-specific implementation.**

The fix uses polling `pg_stat_activity` to observe lock waits. This is tightly coupled to Postgres implementation details. If the codebase ever migrates to another database (or adds multi-DB support), this test breaks.

**Recommendation:** Add a comment linking this test to Postgres 14+, and document the behavior as "Postgres-specific lock-wait observation."

**Issue 2: No fallback test timeout.**

The latch handshake replaces wall-clock timing, but if the handshake code has a bug (e.g., the latch is never released), the test hangs forever instead of failing with a timeout.

**Recommendation:** Wrap the latch.await in a timeout: `latch.await(30, TimeUnit.SECONDS)` to fail fast if the synchronization is broken.

---

### AC14: Video Load-Failure UX — Navigation-Away & Toast Spam Risks

**Issue 1: Navigation-away handling.**

The fix says "retry once with a freshly-requested signed URL, and if it still fails show a dismissible error toast." But what if the user navigates away (e.g., clicks back to the skill list) after the first failure but before the retry completes?

The retry-in-flight will complete and emit a toast on a page the user is no longer viewing. Quasar's toast manager typically shows it on the current page, so it may be orphaned or show on the wrong page.

**Recommendation:** Before emitting the toast, check if the component is still mounted / the page is still active. Add a `beforeUnmount` cleanup to cancel in-flight retries.

**Issue 2: Toast spam on repeated failures.**

If the user clicks "play" on the same video twice (and both fail), they'll see two toast messages. If a video is permanently broken, repeated attempts spam toasts.

**Recommendation:** Add a debounce or rate-limiter on toasts for the same video (e.g., max 1 toast per 10 s).

---

### AC15: authorizePlayback Latency Signal — No Issues Found ✓

Straightforward: add `@Observed` annotation, remove dead logging. Mirrors deferred-98 SLU trend pattern correctly.

**Optional consideration:** Once the metric is emitted, should there be a Grafana alert for high p99 latency? That's out of scope for this AC but worth noting for follow-up.

---

### AC16: Configurable Phone/National-ID Validation — Locale-to-Market Mapping Gap

**Issue 1: Default E.164 pattern scope.**

The AC proposes "permissive E.164: optional `+`, 7–15 digits" — but what about:
- **Short codes** (e.g., 3-digit emergency codes): 7–15 digits rejects them.
- **Extensions** (e.g., `+1-555-0100 x123`): E.164 doesn't support extensions.
- **Country-specific formats** (e.g., Cameroon's 9-digit format without +237 prefix): if a market wants to accept a local format, the config should allow it.

**Recommendation:** Frame the default as "minimum 7 digits (common for most countries)" and allow per-market config to override with shorter/longer patterns or non-standard formats.

**Issue 2: Locale-to-market mapping.**

The AC says "make the rule **config-driven per market**" and "market-driven wording" — but how is "market" determined? If the user's `Locale` is French, are they in Cameroon? France? Belgium? The AC doesn't address this.

**Recommendation:** Clarify the market resolution logic:
- Is market determined by the request's `Accept-Language` header (locale)?
- Or by the user's profile (stored market)?
- Or by the request's originating IP / geolocation?
- Or by an explicit `?market=cm` query param?

Document the precedence (e.g., "explicit `?market` > profile market > locale's region > default").

---

### AC17: Ledger Hygiene — No Issues Found ✓

Mechanical work with sound verification approach (line-for-line matching). Low risk.

---

## Non-Issues (Verified as Sound)

- **AC4 (OTP 409):** Straightforward, mirrors existing patterns.
- **AC8 (Migration conventions doc):** Correctly preserves applied migrations; append-only approach is sound.
- **AC10 (PessimisticLockRetryer instrumentation):** Well-scoped tradeoff documentation.
- **AC15 (authorizePlayback latency):** Simple, follows established pattern.

---

## Recommendations for Dev

1. **Before starting dev:**
   - Verify AC2 executor budget: Are the values in `ExecutorShutdown.java` currently ~48 s total, or has something changed?
   - Verify AC7 word-boundary patch: Is it already landed in HEAD?
   - Clarify AC2 pool count: 6 or 7? Include `storageUploadExecutor`?

2. **During dev, add defensive code:**
   - AC1: Implement idempotency check before refunding; handle reconciliation-record persist failure.
   - AC3: Bound the WARN-once cache to prevent unbounded growth.
   - AC5: Make timeout formula explicit in config; document TLS cert validation strategy.
   - AC6: Explicitly enumerate outcomes (`success` vs. others); add error handling to `echo "result=..."`.
   - AC7: Add regression test for underscore-heavy identifiers.
   - AC9: Use PK-based delete instead of CTID; add job-failure logging.
   - AC11: Use pre-check `requestedBytes > limit - usedBytes` instead of `Math.addExact`.
   - AC13: Wrap latch.await in timeout; document Postgres-specific behavior.
   - AC14: Add navigation-away cleanup and toast rate-limiting.
   - AC16: Document market-resolution logic in config comments.

3. **Test coverage:**
   - AC1: Test refund idempotency (e.g., repeated retries don't double-charge).
   - AC9: Test NULL and non-NULL timestamps, concurrent deletes, job failure.
   - AC13: Test latch timeout failure mode (ensure test fails, not hangs).
   - AC14: Test navigation-away + concurrent retry + repeated toasts.

---

## Conclusion

**Ready for dev with minor clarifications.** The story targets real bugs, and all 16 ACs are implementable. The issues above are **not blockers** but **surface as implementation details during dev** — most can be resolved by reading the existing code (budget math, patch status) or by adding defensive code (idempotency, timeout bounds, error handling).

No false positives; all findings are actionable.
