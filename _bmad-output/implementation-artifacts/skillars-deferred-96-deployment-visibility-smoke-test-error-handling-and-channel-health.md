# skillars-deferred-96: Deployment Visibility — Smoke Test Error Handling & Channel Health

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high

---

## Story Overview

Two critical deployment visibility gaps discovered during the deferred-94 code review that went unaddressed. Current workflow has two failure modes, one of which is completely invisible:

1. **Smoke test completion failure** — If the smoke test step itself *errors* (ssh dies, timeout, permission denied) rather than completing with a `result=fail`, the entire failure-notification chain and auto-revert silently skip with no visibility to operations.

2. **Notification channel invisibility** — Slack webhooks and SMTP configs that are down remain invisible in CI/CD until the next incident (because `continue-on-error: true` hides them). No proactive channel health check exists.

Both are production safety issues affecting incident response and operational awareness.

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
  - `.github/workflows/deploy.yml` line ~109 auto-reverts on `if: steps.smoke.outcome == 'failure' && steps.smoke.outputs.result == 'fail'`
  - `.github/workflows/deploy.yml` line ~188-194 marker runs on `if: always() && steps.smoke.outputs.result == 'fail'`
  - Pre-smoke notifications (lines ~196, ~207) gate on `steps.smoke.outcome == 'skipped'`
  - **If smoke step errors:** `steps.smoke.outputs.result` is never written, so all three conditionals skip silently
  - **Result:** Red run, no notification, no auto-revert, no ops visibility

- **Root cause:** Conditionals assume the smoke step completes (writing an output), but don't handle the error case where it aborts mid-execution.

- **Fix approach (two options, pick one):**

  **Option A (Recommended):** Make smoke step always write outputs, even on error
  - Add `continue-on-error: true` to smoke test step OR
  - Wrap smoke command in shell script that captures exit code and explicitly writes `result=pass|fail|error` regardless of outcome
  - **Pro:** Cleaner, outputs are always available for conditional logic
  - **Con:** Must ensure subsequent steps still fail the job on error (use separate `if: failure()` check)

  **Option B:** Rewrite conditional chains to use `outcome` instead of `outputs.result`
  - Auto-revert: `if: steps.smoke.outcome == 'failure'` (catches both error and explicit failure)
  - Marker: `if: always() && steps.smoke.outcome != 'success'` (fires on error or failure)
  - Pre-smoke notify: `if: steps.smoke.outcome == 'skipped' || steps.smoke.outcome == 'failure'`
  - **Pro:** No changes to smoke step itself
  - **Con:** Slightly less precise (can't distinguish error from failure in notification messaging)

  **Recommendation:** Use **Option A** with smoke step writing `result=error|fail|pass` so conditional messaging can be precise.

- **Files affected:** `.github/workflows/deploy.yml` (smoke step definition + auto-revert + marker + pre-smoke notify conditionals)

- **Verification:**
  - Manual test: Kill ssh mid-execution (e.g., `ssh ... & sleep 1; pkill ssh`) and verify workflow still fails visibly with notification
  - Code inspection: All notification/revert conditionals work on `steps.smoke.outcome` or guaranteed-available outputs
  - Trace through both failure modes: explicit `result=fail` (current working case) and `outcome=failure` (the error case)

- **Test:** No new test framework needed (this is a GitHub Actions workflow, not app code). Verification is manual + code inspection.

- **Rationale:** Deployment failures must be visible ops incidents. Error-mode invisibility defeats the purpose of CI/CD alerting. This is a high-priority safety fix.

### AC2: Add HealthIndicator for notification channel reachability

- **Task:** Implement a Spring Boot `HealthIndicator` that checks SMTP and Slack webhook reachability, so a down channel surfaces in the ops monitoring dashboard instead of silently in the next deploy.

- **Details:**
  - **Why it's needed:** With `continue-on-error: true` on notification steps, a failed Slack webhook or broken SMTP config no longer fails the build — it's now invisible until someone tries to use it.
  - **Requirement:** Check Slack and SMTP connectivity and report in Spring Boot `/actuator/health` endpoint
  - **Check pattern:** 
    - SMTP: Attempt SMTP EHLO handshake to the configured server; report UP if successful, DOWN if connection fails
    - Slack: Attempt a test webhook call (or webhook validation ping if supported by Slack API); report UP if successful
  - **Config:** Read endpoints from app properties (`app.notification.slack-webhook-url`, `app.email.smtp-host`, etc.)
  - **Fallback:** If endpoints are not configured, report UNKNOWN (not an error, just unconfigured)

- **Current state:**
  - `spring-boot-actuator` is already a dependency (used for monitoring)
  - Health checks exist for database, cache, etc.
  - No existing SMTP or Slack health checks
  - App properties already exist for SMTP and Slack configuration

- **Implementation approach:**
  1. Create `SlackHealthIndicator extends AbstractHealthIndicator` in `platform.admin` or `platform.notification` package
  2. Implement `doHealthCheck(Health.Builder builder)` to test webhook
  3. Create `SmtpHealthIndicator extends AbstractHealthIndicator` to test SMTP connectivity
  4. Both should handle timeouts gracefully (fail fast, report DOWN)
  5. Register both with Spring via `@Component` or explicit `@Bean`
  6. Add integration test: mock Slack/SMTP down, verify `/actuator/health` reports DOWN

- **Files affected:**
  - `src/main/java/com/softropic/skillars/platform/admin/health/SlackHealthIndicator.java` (new)
  - `src/main/java/com/softropic/skillars/platform/admin/health/SmtpHealthIndicator.java` (new)
  - `src/test/java/com/softropic/skillars/platform/admin/health/SlackHealthIndicatorIT.java` (new)
  - `src/test/java/com/softropic/skillars/platform/admin/health/SmtpHealthIndicatorIT.java` (new)

- **Verification:**
  - Unit tests: Mock Slack/SMTP, verify UP/DOWN reported correctly
  - Integration tests: Actual Slack webhook call + SMTP EHLO handshake
  - Manual: Curl `/actuator/health` and verify both indicators present and status correct
  - Ops verification: Add to ops dashboard so channel health is visible alongside other app health

- **Test:** New integration tests covering both UP and DOWN scenarios; `/actuator/health` manually verified.

- **Rationale:** Proactive visibility. Channels fail; the app should know and report it. This is standard ops practice (health checks are how ops dashboards detect degradation).

---

## Technical Requirements

- No database schema changes
- No external API additions (Slack/SMTP reachability checks only)
- Health checks follow Spring Boot `HealthIndicator` patterns already used in the app
- Timeout on health checks should be short (< 5s) so `/actuator/health` doesn't become a bottleneck
- SMTP check should not send mail (EHLO only)
- Slack check should not create notifications (webhook validation or test call only, if supported)

---

## Dev Notes

### Why This Story Was Created

This story was discovered during the code review of deferred-94, which fixed the "notification step throws" visibility gap. That fix (adding `continue-on-error: true`) accidentally created a new gap: notification channels can now be down without triggering a build failure. This story addresses the two remaining visibility issues:

1. **Smoke test error invisibility** (F6.1 from review) — the smoke step itself failing to complete
2. **Channel health invisibility** (F6.2 from review) — channels being down without ops knowing

Both were filed in deferred-94's code review but not picked up by that story. This story addresses them comprehensively.

### Re-Verification Against HEAD (Process Learning)

**Citations verified at story creation (2026-09-07):**
- `.github/workflows/deploy.yml:188-194` — `Fail workflow` marker ✓
- `.github/workflows/deploy.yml:109` — auto-revert ✓
- `.github/workflows/deploy.yml:196,207` — pre-smoke notifications ✓
- Deferred-94 code review section: `deferred-work.md:1412-1420` ✓

All line numbers re-verified against HEAD before writing this story (not copied from prior audit).

### Related Items in Deferred Ledger

- **Deferred-94 AC12:** Deployed the first part of the visibility fix (notification `continue-on-error`)
- **Deferred-95 (withdrawn):** Attempted to re-address deferred-94's fixes without realizing they were already merged

---

## Acceptance Checklist

- [ ] AC1: Smoke test step error handling implemented (Option A or B)
- [ ] AC1: Auto-revert conditional re-verified to work in error case
- [ ] AC1: Failure marker conditional re-verified to work in error case
- [ ] AC1: Pre-smoke notifications work in error case
- [ ] AC1: Manual test: workflow still fails visibly when smoke step errors
- [ ] AC2: `SlackHealthIndicator` implemented and registered
- [ ] AC2: `SmtpHealthIndicator` implemented and registered
- [ ] AC2: Both indicators appear in `/actuator/health` endpoint
- [ ] AC2: Integration tests cover UP and DOWN scenarios
- [ ] AC2: Timeout on checks is short (< 5s)
- [ ] All tests pass: `mvn -o verify`
- [ ] No new CI/CD failures introduced
- [ ] Code review green

---

## Success Criteria

✅ Deploy failures are always visible in GitHub Actions, regardless of how smoke test fails  
✅ Notification channel health is proactively reported in ops monitoring  
✅ On-call engineers get immediate notification of deploy results AND channel health  
✅ No regressions in existing deploy workflow  
✅ Health checks don't add latency to `/actuator/health` endpoint

---

## Story Source & Timeline

- **Discovered:** 2026-09-07, during deferred-94 code review
- **Filed in:** `deferred-work.md:1412-1420` (code review deferrals)
- **Scope:** Two independent visibility gaps in deployment infrastructure
- **Priority:** High (affects incident response and ops visibility)
- **Dependencies:** Deferred-94 (context only, no blocker)

