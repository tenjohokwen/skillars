# skillars-deferred-96: Deployment Visibility — Smoke Test Error Handling & Channel Health

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high

---

## Story Overview

Two critical deployment visibility gaps discovered during the deferred-94 code review that went unaddressed. Current workflow has error-handling blind spots:

1. **Smoke test error invisibility** — If the smoke test step itself *errors* (step/runner killed, `$GITHUB_OUTPUT` write fails, or job cancellation timeout) rather than completing with `result=fail`, the auto-revert and all failure notifications silently skip because they lack explicit status-check functions and are therefore `success()`-gated. Result: deploy stays live with zero ops notification.

2. **SMTP channel invisibility** — SMTP configs that are down remain invisible in CI/CD until the next email is sent. With `continue-on-error: true` on notification steps, a down SMTP endpoint no longer fails the build. No proactive channel health check exists.

Both are production safety issues affecting incident response. AC2 requires careful governance (separate health group) to avoid regression of AC1.

**Source:** Deferred-94 code review findings (2026-09-07, `deferred-work.md:1412-1420`); discovered when auditing deploy-workflow visibility fixes.

---

## User Story

**As a** DevOps/SRE engineer and on-call responder,  
**I want** deploy failures to be *always* visible in CI/CD regardless of how the smoke test fails, and notification channels to report health proactively,  
**So that** I can quickly identify when a deploy fails and get notified immediately, even when infrastructure (ssh/SMTP/Slack) is degraded.

---

## Acceptance Criteria

### AC1: Ensure deploy failure visibility even when smoke test step errors

- **Task:** Fix the workflow so that if the `Smoke test` step itself *errors* (doesn't complete normally), the failure notification and auto-revert still execute and the run is visibly marked as failed.

- **Current problem:**
  - Auto-revert at deploy.yml:109 gates on `if: steps.smoke.outcome == 'failure' && steps.smoke.outputs.result == 'fail'`
  - Marker at deploy.yml:188-194 gates on `if: always() && steps.smoke.outputs.result == 'fail'`
  - Four result-based notification steps gate on `steps.smoke.outputs.result == 'pass|fail'`
  - Pre-smoke notifications gate on `steps.smoke.outcome == 'skipped'`
  - **If smoke step errors** (step/runner killed, `$GITHUB_OUTPUT` write fails, or job cancelled): `steps.smoke.outputs.result` is never written
  - **Additional constraint:** GitHub Actions implicitly gates conditionals without status-check functions on `success()`. When smoke step errors, `success()` becomes false, so auto-revert/notify conditionals without `failure()`/`always()` are false even if the condition part evaluates true.
  - **Result:** No auto-revert, no failure notification, deploy stays live with zero alerts. Deploy stays marked red (not the messaging issue) but ops gets no notification.

- **Root cause:** Twofold: (1) Smoke step may not write output on error, and (2) Most downstream conditionals lack explicit status-check functions and are therefore `success()`-gated, causing them to skip when smoke step fails.

- **Fix approach (two options, pick one):**

  **Option A (Recommended):** Wrap smoke command to always write output, AND add status functions to downstream conditionals
  - Wrap smoke step command in shell script: `set +e; <smoke-test>; echo "result=$(if [ $? -eq 0 ]; then echo pass; else echo fail; fi)" >> $GITHUB_OUTPUT`
  - Change auto-revert to: `if: failure() && (steps.smoke.outputs.result == 'fail' || steps.smoke.outcome == 'failure')`
  - Change four result-notify steps to: `if: steps.smoke.outputs.result == 'pass|fail'` (already implicit `success()`-gated, but add `failure()` guard: `if: (success() || failure()) && steps.smoke.outputs.result == 'pass|fail'`)
  - Keep marker as: `if: always() && steps.smoke.outcome != 'success'` (already correct)
  - Change pre-smoke notifications to: `if: failure() && (steps.smoke.outcome == 'skipped' || steps.smoke.outcome == 'failure')`
  - **Pro:** Outputs always available; each conditional has explicit status guard; precise failure messages possible
  - **Con:** More complex; requires shell wrapper

  **Option B:** Rewrite conditionals to use `outcome` + explicit status functions (no smoke-step changes)
  - Auto-revert: `if: failure() && steps.smoke.outcome == 'failure'` (must include `failure()`)
  - Result-notify steps: add `failure()` guard: `if: (success() || failure()) && steps.smoke.outputs.result == 'pass|fail'`
  - Marker: keep `if: always() && steps.smoke.outcome != 'success'`
  - Pre-smoke notifications: `if: failure() && (steps.smoke.outcome == 'skipped' || steps.smoke.outcome == 'failure')` (fix: was missing `failure()`)
  - **Pro:** Minimal smoke-step changes
  - **Con:** Requires coordination of four separate conditional rewrites; can't distinguish error from explicit fail in messages

  **Additional requirements (both options):**
  - Add `ConnectTimeout=10 -o BatchMode=yes` to all ssh calls to prevent multi-hour hangs
  - Add `timeout-minutes: 15` to the smoke test step to cap job cancellation risk
  - Change marker to also catch `cancelled()`: `if: always() && steps.smoke.outcome != 'success'` (already works; `cancelled()` is covered)

  **Recommendation:** Use **Option A** for cleaner messaging, but either option works if conditionals include explicit status functions.

- **Files affected:** `.github/workflows/deploy.yml` (smoke step definition + auto-revert + marker + pre-smoke notify conditionals)

- **Verification:**
  - Manual test (actual error reproduction): Add `|| exit 1` after a smoke check to force the step to fail mid-execution, or add `kill $$` to kill the step shell itself; verify auto-revert and notifications both fire
  - Code inspection: Verify all auto-revert/notify/marker conditionals include explicit status functions (`failure()` or `always()`) and that no conditionals rely solely on `steps.smoke.outputs.result` without fallback to `outcome`
  - Trace both modes: (1) smoke completes with `result=fail` (existing working case) and (2) smoke errors before writing output (the fix being tested)
  - SSH timeout test: temporarily add `ConnectTimeout=10s` to ssh, test with network black-holed runner to verify `timeout-minutes: 15` kills the job and marker still fires

- **Test:** No new test framework needed (this is a GitHub Actions workflow, not app code). Verification is manual + code inspection.

- **Rationale:** Deployment failures must be visible ops incidents. Error-mode invisibility defeats the purpose of CI/CD alerting. This is a high-priority safety fix.

### AC2: Add SMTP HealthIndicator (with governance safeguards)

- **Task:** Implement a Spring Boot `HealthIndicator` for SMTP channel reachability in a separate health group so it does not interfere with the deploy smoke test, which relies on `/manage/health` returning `"status":"UP"`.

- **Why it's needed:** With `continue-on-error: true` on notification steps, a failed SMTP config no longer fails the build — it's now invisible until someone tries to send mail. A proactive health check allows ops to detect email channel failures before the next actual email.

- **Critical constraint (F8 from review):** The smoke test polls `http://localhost:8367/manage/health` for `"status":"UP"`. Any `HealthIndicator` registered without a dedicated health group rolls into the aggregate status. If `SmtpHealthIndicator` reports `DOWN` due to a transient SMTP timeout, the smoke test of the *next* deploy fails, triggering auto-revert of a healthy release (regression). **Solution:** Define a separate health group in `application.yaml` for the SMTP check so it's excluded from the default smoke-test health group.

- **Details:**
  - **SMTP only (NOT Slack):** The app has no Slack integration or configuration. Slack notifications are sent by GitHub Actions workflows (from GitHub runners), not the Spring app. A Slack `HealthIndicator` in the Spring app (a) has no configuration to read, (b) tests the wrong path (app → Slack, not runner → Slack), and (c) cannot health-check incoming webhooks without posting a message. Slack channel health checks belong in the **workflow** if needed (a lightweight heartbeat job posting to `#deploys`), not the app.
  - **SMTP config:** App has two mail providers (`email.providerConfigs`): gmx and gmail; plus a separate `spring.mail.*` config. The `HealthIndicator` should check **SMTP connectivity only** (EHLO handshake), not auth, since auth failures (expired app-passwords) won't surface in a connection test. Caveat: EHLO will show UP even if credentials are broken; this check catches infrastructure issues (network, firewall) not configuration issues (expired passwords). Accepted as a practical tradeoff.
  - **Endpoint:** The actual management endpoint is `http://<host>:8367/manage/health` (not `/actuator/health`; port 8367, path `/manage`). Details are only visible to authenticated `ROLE_ADMIN`.

- **Implementation approach:**
  1. Create `SmtpHealthIndicator extends AbstractHealthIndicator` in `platform.notification` package
  2. Implement `doHealthCheck(Health.Builder builder)` to attempt SMTP EHLO to the primary mail provider (gmx or gmail from `email.providerConfigs`)
  3. Read SMTP endpoint from `email.providerConfigs[0].host` (or make it configurable); handle both providers if needed (rollup: all-down = DOWN, any-up = UP)
  4. Handle timeouts: set socket timeout to 5 seconds, fail fast, report DOWN if unreachable
  5. **Register to a separate health group:** Add to `application.yaml` under `management.endpoint.health.group.notification` containing only this indicator, so it's excluded from the default group used by the smoke test
  6. Mark bean with `@ConditionalOnProperty` so it doesn't register if SMTP config is absent

- **Files affected:**
  - `src/main/java/com/softropic/skillars/platform/notification/health/SmtpHealthIndicator.java` (new)
  - `src/test/java/com/softropic/skillars/platform/notification/health/SmtpHealthIndicatorTest.java` (new, unit tests with mocked sockets)
  - `src/main/resources/application.yaml` (add health group definition)

- **Verification:**
  - Unit tests: Mock `Socket` to simulate UP/DOWN/timeout; verify indicator reports correctly
  - Manual: Curl `http://localhost:8367/manage/health` (default group, no SMTP indicator) should return `"status":"UP"` regardless of SMTP; curl `http://localhost:8367/manage/health/notification` (explicit group, includes SMTP) to see channel status
  - Smoke test still passes: verify deploy smoke test at deploy.yml:96–97 still passes even if SMTP is unreachable
  - Ops dashboard: add `/manage/health/notification` scrape to ops dashboard for channel visibility (separate from liveness checks)

- **Test:** Unit tests with mocked Socket for UP/DOWN/timeout scenarios; no live SMTP integration test (avoid credential exposure and rate limiting on external providers).

- **Rationale:** Proactive channel visibility without breaking existing liveness checks. The deploy smoke test must remain isolated from external-service failures (SMTP timeouts, Slack downtime) so deploy automation is resilient to transient external issues.

---

## Technical Requirements

- No database schema changes
- No new external dependencies or API additions
- AC1: Workflow syntax must be valid; auto-revert and all notifications must include explicit status-check functions (`failure()` or `always()`)
- AC1: SSH calls must have connection timeout + batch mode; smoke step must have `timeout-minutes` set
- AC2: `SmtpHealthIndicator` follows Spring Boot `AbstractHealthIndicator` patterns already used in the app
- AC2: Health indicators must be registered in a separate health group (`management.endpoint.health.group.notification`) so they don't interfere with the smoke-test group
- AC2: Socket timeout on SMTP check must be short (5s) to avoid cascading timeouts in health scrapes
- AC2: SMTP check must be EHLO-only (no mail send, no auth attempt)
- AC2: No Slack `HealthIndicator` — app has no Slack config; workflow-level health checks (if needed) are out of scope
- AC2: Indicator must not register if email config is absent (`@ConditionalOnProperty`)

---

## Dev Notes

### Critical Revision (2026-09-07 Post-Review)

Initial story draft had significant flaws caught by senior review. Key corrections:

1. **AC1 root cause was incomplete:** Story initially blamed "conditionals assume output is written" but the deeper issue is that GitHub Actions implicitly gates conditionals without status-check functions on `success()`. When smoke step errors, `success()` is false, so auto-revert/notify skip even if logic is correct. **Fix:** Add explicit status functions (`failure()` or `always()`) to all downstream conditionals.

2. **Option B was broken:** "Option B" as written (auto-revert on `if: steps.smoke.outcome == 'failure'`) would never execute because it lacks `failure()` guard. **Fix:** All conditionals that must survive a failed smoke step must include explicit status-check functions.

3. **AC2 regressed AC1:** Custom HealthIndicators roll into the aggregate `/manage/health` status, which the smoke test greps for `"status":"UP"`. A brief SMTP timeout would fail the *next* deploy's smoke test, auto-reverting a healthy release. **Fix:** Define a separate health group that excludes the SMTP indicator from the default group.

4. **Slack integration doesn't exist:** Story proposed a Slack webhook `HealthIndicator`, but the app has no Slack configuration, no Slack client code, and no Slack integration. GitHub Actions handles Slack notifications from runners, not the app. **Fix:** Dropped Slack `HealthIndicator` entirely; SMTP only.

5. **Property names were fabricated:** Story cited `app.notification.slack-webhook-url` and `app.email.smtp-host`, but these don't exist in `application.yaml`. **Fix:** Corrected to actual config: `email.providerConfigs[*]` and `spring.mail.*`.

6. **Missing critical edge cases:** Story omitted job-cancellation timeout handling, SSH hang prevention, and EHLO-only caveat (won't catch credential expiry). **Fix:** Added `timeout-minutes` to smoke step, connection timeouts to ssh, and clarified what EHLO checks/doesn't check.

7. **Wrong endpoint path:** Story repeatedly said `/actuator/health` but the app uses `/manage` on port 8367. **Fix:** Changed all references to `http://<host>:8367/manage/health`.

**See `_bmad-output/implementation-artifacts/story-review.md` for full audit (17 findings).**

### Story Origin & Timeline

This story was discovered during the code review of deferred-94, which fixed the "notification step throws" visibility gap. That fix (adding `continue-on-error: true`) accidentally exposed a second gap: notification channels can now be down without triggering a build failure. This story addresses the two remaining visibility issues:

1. **Smoke test error invisibility** — the smoke step itself failing to complete due to error/timeout/cancellation
2. **SMTP channel invisibility** — SMTP configs being down without proactive health checks

Both were filed in deferred-94's code review but not picked up by that story.

### Re-Verification Against HEAD (Process Learning)

**Citations verified at story creation (2026-09-07), re-verified after review (2026-09-07):**
- `.github/workflows/deploy.yml:109` — auto-revert conditional ✓
- `.github/workflows/deploy.yml:188-194` — `Fail workflow` marker ✓
- `.github/workflows/deploy.yml:196, 207` — pre-smoke notifications ✓
- `.github/workflows/deploy.yml:93-105` — smoke test loop with error handling ✓
- `application.yaml:118-128` — spring.mail config ✓
- `application.yaml:144-158` — email.providerConfigs (gmx, gmail) ✓
- `application.yaml:360-367` — management endpoint config (port 8367, path `/manage`) ✓

All line numbers re-verified and corrected after review. No Slack config exists anywhere in codebase.

### Related Items in Deferred Ledger

- **Deferred-94 AC12:** Deployed the first part of the visibility fix (notification `continue-on-error`)
- **Deferred-95 (withdrawn):** Attempted to re-address deferred-94's fixes without realizing they were already merged

---

## Acceptance Checklist

**AC1:**
- [ ] Smoke test step wrapped to write `result=pass|fail|error` (Option A) OR all downstream conditionals rewritten with status functions (Option B)
- [ ] Auto-revert conditional includes `failure()` guard: `if: failure() && …`
- [ ] All four result-notify steps include status guard: `if: (success() || failure()) && steps.smoke.outputs.result == 'pass|fail'`
- [ ] Failure marker kept as: `if: always() && steps.smoke.outcome != 'success'`
- [ ] Pre-smoke notifications include `failure()` guard: `if: failure() && …`
- [ ] SSH calls have `ConnectTimeout=10 -o BatchMode=yes` to prevent multi-hour hangs
- [ ] Smoke test step has `timeout-minutes: 15` to cap job-cancellation risk
- [ ] Manual test: Step error (kill step or exit mid-execution) triggers auto-revert AND failure notification
- [ ] Workflow syntax valid (dry-run or lint check passes)

**AC2:**
- [ ] `SmtpHealthIndicator` implemented in `platform.notification` package
- [ ] HealthIndicator uses EHLO handshake only (no mail send, no auth)
- [ ] Socket timeout set to 5 seconds
- [ ] Bean registered with `@ConditionalOnProperty` so it doesn't appear if email config absent
- [ ] Health group defined in `application.yaml`: `management.endpoint.health.group.notification` containing SmtpHealthIndicator only
- [ ] Smoke test health group (default) excludes SMTP indicator
- [ ] Unit tests cover UP/DOWN/timeout scenarios (no live SMTP calls)
- [ ] Manual verification: `/manage/health` returns `"status":"UP"` (smoke-test group); `/manage/health/notification` shows SMTP status separately
- [ ] Acceptance checklist verified: smoke test passes even if SMTP unreachable
- [ ] No Slack indicator created (out of scope — app has no Slack config)

**Integration:**
- [ ] All tests pass: `mvn -o verify`
- [ ] Deploy smoke test still passes (no regression)
- [ ] No new CI/CD failures introduced
- [ ] Code review green

---

## Success Criteria

✅ Deploy failures are always visible in CI/CD, even when smoke test step errors (no auto-revert/notification skips)  
✅ Auto-revert always runs when smoke test fails (handles error, timeout, and normal failure modes)  
✅ Failure notifications always sent (includes both success and failure paths)  
✅ SMTP channel health proactively reported in ops monitoring dashboard (separate health group)  
✅ On-call engineers notified of deploy results immediately (no silent failures)  
✅ No regression: deploy smoke test remains resilient to SMTP timeouts or external service failures  
✅ Health checks don't interfere with existing liveness/readiness probes  
✅ No Slack integration in scope (app has no Slack config; GitHub Actions handles Slack deployment notifications)

---

## Story Source & Timeline

- **Discovered:** 2026-09-07, during deferred-94 code review
- **Filed in:** `deferred-work.md:1412-1420` (code review deferrals)
- **Scope:** Two independent visibility gaps in deployment infrastructure
- **Priority:** High (affects incident response and ops visibility)
- **Dependencies:** Deferred-94 (context only, no blocker)

