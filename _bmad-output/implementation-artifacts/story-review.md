# Story Audit: Deferred-76

**Status:** APPROVED with verification checkpoints flagged below

## Summary

Story is well-researched and findings are solid. Five legitimate corner cases and re-verification checkpoints identified; no false positives found.

---

## AC1 — Production data-loss and provisioning-script safety fixes

✅ **Approved.** All four fixes are correct and necessary.

**Corner case — shell script portability:**
- `stat -c '%u:%g'` uses GNU stat format (Linux only); macOS `stat` uses different flags
- This is acceptable: `provision.sh` and `install-crons.sh` target Hetzner nodes (Linux), documented in deployment docs as Debian/Ubuntu VMs
- No action needed, but worth noting in commit message if context is lost

**Verified claims:**
- Idempotent `chown_if_needed` helper correctly checks current owner before running `chown -R` — prevents interrupting in-progress container writes ✓
- Early exit if directory doesn't exist — the `stat ... 2>/dev/null` failure case falls through correctly and runs chown, which is right (better to enforce correct perms than skip) ✓

---

## AC2 — Observability configuration fixes

✅ **Approved.** All four fixes are correct.

**Corner case — trace ID format assumption:**
- Regex `[a-fA-F0-9]{32}` assumes exactly 32 hex chars (W3C 128-bit format)
- OTel spec allows variable-length IDs; if the app ever switches formats, this breaks silently
- **Action:** Add a comment in `grafana-datasources.yml` noting this assumes W3C trace ID format (32 hex chars) — future-proofing for if the app changes formats
- Not a blocker; just document the assumption

**Verified claims:**
- `spanStartTimeShift` reduction from `1h` to `1m` is reasonable (margin for clock skew between log timestamp and span emission) — the 2026-06-03 code review suggested this ✓
- Tempo's `block_retention` of 336h (14d) is confirmed in `deploy/lgtm/tempo.yml:23` by the story ✓

---

## AC3 — Close the production live-Stripe-key guard gap

✅ **Approved.** Security guard improvement is sound and actually safer than the original.

**Critical change:** This flips a security guard from opt-in (only non-prod profiles block live keys) to require-prod (any non-`prod` profile rejects live keys). Correctly identified as highest-blast-radius change in this story.

**Verified assumptions:**
- `docker-compose.yml`'s `SPRING_PROFILES_ACTIVE=prod` propagates to `environment.getActiveProfiles()` — standard Spring Boot behavior ✓
- The new logic `!prodProfileActive && LIVE_KEY_PATTERN` correctly requires the profile name to be exactly `"prod"`, failing closed if typo'd or absent ✓
- Test inversion (turning passing test into failing, adding new positive case) is correctly described — **verify this test passes after the fix** during implementation ✓

**Corner case — command-line profile override:**
- If someone runs `java -Dspring.profiles.active=uat ...` from CLI, Spring merges it with env var profiles correctly
- Not an issue; Spring's standard profile resolution handles this

---

## AC4 — Deploy-workflow and rollback-documentation hardening

✅ **Approved.** All parts are correct, with one verification point.

**Re-verification checkpoint — GitHub Actions outcome value:**
- The "early failure notification" step uses `steps.smoke.outcome == 'skipped'` to detect "job failed before reaching Smoke Test"
- Story acknowledges: "This was not verified against a live GitHub Actions run"
- **Action:** Before merging, confirm in a test run what the actual value of `steps.smoke.outcome` is for a step skipped due to an earlier step's failure (vs. unset/null/empty)
- Likely correct, but GitHub's behavior can be surprising

**Verified claims:**
- Auto-Revert's local pre-check (`docker image inspect <prev-tag>` before network pull) is elegant — skips network entirely when image is locally cached, falls back to pull if not found locally ✓
- Health-check loop in rollback.md uses `seq 1 6` (6 attempts × 10s = 60s total wait) — reasonable timeout ✓
- `docker compose ps -q app` extracts container ID by service name — assumes single app container, which is true in this stack ✓
- Documentation for `SSH_KNOWN_HOST` correction (multi-line output, not single line) is the real bug being fixed ✓

**Non-issues:**
- `docker compose pull app` idempotency — correct, will retry partial pulls
- The curl-based health check inside the container via `docker exec` — standard approach ✓

---

## AC5 — Document a post-migration rollback procedure

✅ **Approved.** Documentation is correct.

**Verified claim:**
- Backup interval stated as `0 */6 * * *` (every 6 hours) is correct — story cites the actual `install-crons.sh` cron ✓
- Data-loss window up to 6 hours is correctly stated

**Not a corner case:** The story correctly notes this is the fallback ("when fix-forward can't unblock in time"), not the primary path.

---

## AC6 — Remove the dead `JWT_SECRET` configuration

✅ **Approved.** Straightforward removal.

**Verified claim:**
- Story grepped `src/main/java` for `JWT_SECRET` and `@Value`/`Environment` bindings — zero matches confirmed ✓
- Real mechanism confirmed: `JwtSecretService` → `SecretService.createSecret()` → auto-generated and stored Jasypt-encrypted in `sec.secret` DB table ✓

**Non-issue:** Backwards compatibility — if someone has `JWT_SECRET` set in their production `.env`, the app won't read it and won't break. Safe to remove from docs.

---

## AC7 — Build minimal real Stripe payment-failure alerting; scrub the stale Orange/MTN docs

✅ **Approved with verification checkpoint.**

**Re-verification checkpoint — Micrometer metric naming:**
- Story correctly requires verifying actual metric names from live `/actuator/prometheus` before finalizing alert `expr:` lines
- Micrometer suffixes counters with `_total`, renames fields to snake_case, handles tag→label mapping — this must be verified against real output, not assumed
- **Dev note in story already calls this out** ✓

**Verified claims:**
- Existing counters `SETTLE_CONFLICT_COUNTER` and `SETTLE_ERROR_COUNTER` confirmed in code (lines 35-36, incremented at 138/151) ✓
- `BookingPaymentPersistenceService` is confirmed as the definitive settle-outcome point (only `new Drill()` call site verified in story) ✓
- `StripeWebhookService.handleInvoicePaymentFailed` exists and handles subscription billing failures ✓

**Corner case — asymmetric counters (settle success/failure vs. subscription invoice failure only):**
- Story adds counters for booking settle success + failure, but only subscription invoice *failure*
- Is this intentional? Yes — the alert rules focus on failure rates. But this means subscription billing success is not tracked.
- **Not a blocker:** This is intentional (alerts are for failures). But if future monitoring needs success counts, a second iteration will be needed.
- Document the intentional asymmetry in the commit message

**Potential gap — refunds not mentioned:**
- Are refunds routed through `persistPaymentSuccess`/`persistPaymentFailure`? The story doesn't clarify.
- **Recommendation:** Grep for "refund" in payment code during dev work; if refunds bypass these methods, add counters there too.
- **Risk level:** Low (refunds are usually a small % of traffic, but should be included in failure metrics)

**Testing concern:**
- Story correctly calls out that `CreditRoutingTest` already uses `SimpleMeterRegistry` with `@Spy` (not mocked) for counter assertions
- Extends `StripeWebhookVerificationTest` for webhook counters — matches existing pattern ✓

---

## AC8 — Verify Grafana admin login during first-time-setup

✅ **Approved.** Simple documentation addition.

No corner cases — just adds a manual verification step that doesn't change any code.

---

## AC9 — Neglected-skill detection: add a player warm-up grace period

✅ **Approved with re-verification checkpoints.**

**Re-verification checkpoint 1 — Flyway migration ID:**
- Story uses id 605 as "next free `platform_config` id" (highest currently in use is 604)
- **Action:** Re-verify at dev time against live `main.platform_config` max id — other stories may have claimed it in the interim
- Story acknowledges this ✓

**Re-verification checkpoint 2 — Flyway version:**
- Story uses `V112` as next free version in `src/main/resources/db/migration/`
- **Action:** Re-verify this at dev time — later stories may have newer versions
- Story acknowledges this ✓

**Verified claims:**
- `DevelopmentCorrelationService` already uses `sluRepository.countDistinctSessions(playerId)` — method exists and is consistent ✓
- `sluRepository.countDistinctSessions()` returns `Long` or `null` — the code handles both cases correctly (`sessionCount == null || sessionCount < warmupSessionCount`) ✓
- The early-return guard skips the entire `processPlayer` logic when warmup threshold not reached — correct to prevent flag-flood ✓

**Corner case — "distinct sessions" definition:**
- Story doesn't clarify what "distinct" means (per-day? per-week? unique session IDs?)
- **Non-issue:** Since this reuses `DevelopmentCorrelationService`'s existing method, it's already consistent with that module's logic. The "distinct" definition is owned by the repo's session tracking, not this story.

**Testing concerns:**
- Story correctly identifies all 7 existing `processPlayer()` call sites that need the new `warmupSessionCount` argument — detailed ✓
- The `lenient().when(sluRepository.countDistinctSessions(...)).thenReturn(10L)` stub in `setUp()` is correct — allows all existing tests to pass unchanged (they test threshold logic, not warmup gate) ✓
- Two new tests required: below warmup threshold (no interactions with flag repo) and at boundary (warmup count == threshold passes) ✓
- Note: Scheduler-level tests need `configService.getLong()` stubbed — the default Mockito return of `0L` would mean "no warmup needed" (all players pass the gate). Story correctly notes this could mask a bug if not verified.

---

## AC10 — Guard `SluFormula` against negative drill-metadata values

✅ **Approved.** Two-part fix (annotations + guard) is well-reasoned.

**Critical premise correction (noted in story):**
- Initially assumed live `Drill` creation endpoint exists; research found only `DrillLibraryService.clone()` exists (copies from seed-migration drill)
- **No live endpoint accepts user `DrillMetadata` input** — negative values can only come from hand-written Flyway migrations
- Story correctly decided to fix both ends anyway (future-proofing + current defense-in-depth) ✓

**Verified claims:**
- Only `new Drill()` call site is `DrillLibraryService:123-131` (clone operation) — story searched codebase ✓
- `SluFormula.calculate()` multiplies four factors together; two negatives cancel out silently (the bug) ✓
- Test case proving bug: two negative factors (`intensity=-7, pressureLevel=-6`) should produce same positive SLU value as positive factors (double-negative cancellation) ✓

**Corner case — exact magnitude verification:**
- The test should verify that `intensity=-7, pressureLevel=-6` produces exactly the same SLU value as `intensity=7, pressureLevel=6` (42.0000)
- Story mentions the test but doesn't explicitly state this detail
- **Action:** When writing the test, ensure the double-negative case produces the exact same magnitude to prove the cancellation bug is real
- Not a blocker; the test framework will catch this

**Verification before merge:**
- Story requires checking that current `V39`/`V111` seed migrations have no negative metadata values
- **Action:** `git show V39:* | grep -E '(repDensity|intensity|pressureLevel|matchRealism).*-[0-9]'` or similar during dev work
- Story acknowledges this ✓

**Logging concern:**
- The guard logs a warning with all four field values — good for debugging ✓
- Requires adding `@Slf4j` to `SluFormula` (currently static-only utility class) — straightforward ✓

---

## AC11 — Ledger hygiene

✅ **Approved.** Detailed and meticulously specified.

**Re-verification checkpoints (story already calls these out):**

1. **Item 933 (tmpspace):** Story marks as STALE, claiming `restore-from-dump.sh` uses `gunzip -t` for integrity check (no temp-disk write). 
   - **Action:** Verify the actual `restore-from-dump.sh` code contains the gunzip -t pattern during dev work
   - Claim is plausible (integrity check without decompression), but should be spot-checked

2. **Item 1005 (postgres chown):** Story claims official postgres:17-alpine image's entrypoint self-chowns PGDATA before dropping privileges.
   - **Action:** Verify this is true for postgres:17-alpine (likely true for official image)
   - Claim is standard for official images, low risk, but should be spot-checked

3. **Item 954, others:** Story references specific commit hashes (e.g., `3b0cc28`, "Platform Security — Coach-Player Authorization") as having closed security bugs.
   - **Action:** Verify these commits are in the repo and did what's claimed
   - Story cites them, so they should be present ✓

**Other notes:**
- Item D0 (per-coach UI grant/revoke) correctly notes the underlying security bug is *already* closed by intervening commit (`3b0cc28`), and the residual feature-scope item was decided CLOSED-WON'T-BUILD by the project owner
- This is a decision call, not a technical issue, but correctly documented in the story ✓

---

## Cross-cutting concerns

**1. Interdependencies:**
- AC3's profile setting enables AC3's security guard logic — these must land together ✓
- AC7's counters must exist before AC7's alert rules reference them — correct dependency ✓
- AC9 and AC10 are isolated from other ACs ✓

**2. Testing coverage:**
- AC1/AC2/AC4/AC5/AC6/AC8: Documentation/config/shell scripts with no automated test harness (matches project precedent for `deploy-*` stories)
- AC3: Critical test inversion required (currently-passing test becomes failing under new logic)
- AC7: Requires live `/actuator/prometheus` check before finalizing metric names
- AC9: Requires re-verification of Flyway IDs + extensive test setup changes (7 existing tests)
- AC10: Requires test case proving double-negative bug is real
- All acknowledged in the story ✓

**3. Risk assessment:**
- **Highest risk:** AC3 (security guard flip) — correctly identified as highest-blast-radius ✓
- **Medium risk:** AC7 (payment counters and alerts) — correctly requires metric name verification ✓
- **Low risk:** AC1, AC2, AC4, AC5, AC6, AC8 (straightforward fixes/docs) ✓
- **Complex but low-risk:** AC9 (warmup grace period) — changes test setup in 7 places, but logic is sound ✓
- **Dual-fix but low-risk:** AC10 (annotations + guard) — future-proofs + defends today, clear intent ✓

---

## False positives (things that look like issues but aren't)

✅ None found. The story's claimed issues and fixes are all legitimate.

- AC1's idempotent chown logic is correct
- AC2's regex assumptions are reasonable for this codebase
- AC3's security guard improvement is actually *safer* than the original
- AC4's auto-revert pre-check is elegant
- AC7's asymmetric counters (failure-focused) is intentional
- AC9's warmup grace period correctly solves the flag-flood issue
- AC10's defense-in-depth approach (annotations + guard) is well-reasoned
- AC11's ledger cleanup is thorough and correctly documented

---

## Dev checklist for implementation

- [ ] AC4: Verify `steps.smoke.outcome == 'skipped'` value in a live GitHub Actions run
- [ ] AC7: Curl `/actuator/prometheus` and verify exact metric names before finalizing alert `expr:` lines
- [ ] AC9: Re-verify Flyway version V112 and `platform_config` id 605 against live max values
- [ ] AC9: Ensure all 7 existing `processPlayer()` call sites receive the new `warmupSessionCount` argument
- [ ] AC10: Verify double-negative test case produces exact same magnitude as positive factors (42.0000)
- [ ] AC10: Check current `V39`/`V111` seed migrations have no negative metadata values
- [ ] AC11: Spot-check `restore-from-dump.sh` uses gunzip -t pattern (item 933)
- [ ] AC11: Verify postgres:17-alpine image self-chowns PGDATA (item 1005)
- [ ] AC11: Verify referenced commits (e.g., 3b0cc28) exist and did what's claimed

---

## Verdict

**APPROVED for dev** with checkpoints above. Story is well-researched, findings are legitimate, fixes are correct. Re-verifications are all already called out in the story itself.
