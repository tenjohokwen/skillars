# Story: skillars-deferred-94-deploy-hardening-security-operational-and-listener-safety

## Story Identifier
- **Story Key:** skillars-deferred-94
- **Epic:** Deferred Work Backlog
- **Status:** done
- **Priority:** High (security + operational safety + listener reliability)
- **Completed:** 2026-09-07

---

## Story Summary

A hardening pass across deployment scripts, infrastructure security, and operational clarity. This story addresses deploy-* infrastructure gaps (PGPASSWORD exposure via correct pattern, hardcoded UID documentation, alert volume-mount dependency, disk space alert backstops) and clarifies existing design decisions (video moderation email handling, payment transaction semantics). Drops AC11 (awscli v2 not in apt) and AC13 (monitoring regression risk) based on code review findings.

---

## User Story Statement

**As an** operations/platform team  
**I want** the deployment stack hardened against credential exposure, misconfigured containers, and silent listener failures  
**So that** production incidents are prevented, credential leaks are closed, and operational safety is strengthened

---

## Acceptance Criteria

### **AC1: PGPASSWORD Exposure — All 5 Occurrences Closed**
- [ ] **Locations:** `deploy/backup/pg-backup.sh:32`, `deploy/backup/restore-from-dump.sh:134`, `restore-from-dump.sh:137`, `restore-from-dump.sh:143`, `restore-from-dump.sh:152` (run_psql helper)
- [ ] **Spec:** Use environment-variable inheritance: `PGPASSWORD="${POSTGRES_PASSWORD}" docker exec -e PGPASSWORD "$CID" psql ...`
  - The bare `-e PGPASSWORD` (no `=value`) tells Docker to copy the value from its own environment
  - The `VAR=val cmd` prefix goes into the environment, not into argv — not visible in `ps aux`
  - Works with arbitrary password characters (`$`, backticks, backslashes)
- [ ] **Example pattern:**
  ```bash
  PGPASSWORD="${POSTGRES_PASSWORD}" docker exec -e PGPASSWORD "$APP_CID" psql -U postgres -h localhost -c "DROP DATABASE ..."
  ```
- [ ] **Verification:** `grep -rE 'docker exec .*-e +PGPASSWORD=' deploy/` returns zero matches (no `=value` suffix)
- [ ] **Why not heredoc:** Quoted heredoc (`<<'PGPW'`) suppresses `$` expansion — the password becomes literal `$POSTGRES_PASSWORD` string, breaking auth. Unquoted heredoc mangles passwords containing `$`, backticks, or backslashes.

### **AC2: Hardcoded Container UIDs — Document Correctly**
- [ ] **Correct UID table:** `provision.sh:597–603` and `restore-from-volume-backup.sh:93–99` show:
  - `prometheus 65534:65534` (nobody)
  - `loki 10001:10001` (loki)
  - `tempo 10001:10001` (tempo)
  - `grafana 472:472` (grafana)
  - `redis 999:1000` (redis)
  - `traefik 65534:65534` (nobody, shares with node_exporter)
  - `postgres` uses **no chown** — relies on image entrypoint
- [ ] Add a guard comment in both `provision.sh` and `restore-from-volume-backup.sh` (around the chown lines):
  ```bash
  # Container UIDs are tied to specific image versions (prometheus, loki, tempo, grafana, redis, traefik).
  # Update these chown calls if the corresponding docker-compose.yml image versions change.
  ```
- [ ] **Note:** All images in `docker-compose.yml` are already version-pinned (e.g., `postgres:17-alpine`, `grafana/grafana:11.4.0`). UIDs only change on deliberate image bumps, which go through review. No need to snapshot in `.env.example`.
- [ ] **Verification:** Comments added to lines ~597–603 (provision.sh) and ~93–99 (restore-from-volume-backup.sh) with accurate UID list

### **AC3: APP_CID Capture Race — Fail Fast with Diagnostic**
- [ ] Current state: `restore-from-dump.sh:197-199` already fails fast with diagnostic when APP_CID is empty
- [ ] **Status:** Already mitigated by deferred-93/skillars-uat-6 AC5; verify no regression and document the remaining narrow TOCTOU
- [ ] Add inline comment: "Single unretried `docker compose ps -q app` can race container registration; slow registration aborts restore. Retry outside script if observed."
- [ ] **No code change required** — document only

### **AC4: Double Alertmanager Notification Risk — Design Gate**
- [ ] Current state: `alerts.yml` and `grafana-alerts.yml` both define infra alerts; no Alertmanager deployed yet
- [ ] If Alertmanager is added in the future, add a **`prometheus.alertmanager` entry in `docker-compose.yml`** with an explicit note that **only one of {Prometheus rules, Grafana alerts} should fire the same alert** — recommend disabling Prometheus rules and using Grafana-only
- [ ] Add a comment in `docker-compose.yml`: "DECISION: Alertmanager integration will use Grafana alerts exclusively; remove or disable corresponding Prometheus alert rules when Alertmanager is added"
- [ ] **Spec requirement:** No duplicate notifications until Alertmanager is actually deployed; when it is, ops must audit and reconcile

### **AC5: node_exporter Network Isolation — Scope Clarification**
- [ ] Current state: skillars-deferred-88 AC8 narrowed the exposure: node_exporter sits on `skillars-observability` alone; `app` and `grafana` join both networks
- [ ] Add clarification comment in `docker-compose.yml`: "node_exporter is isolated on `skillars-observability`; `app`/`grafana` can reach it as they join both networks. A compromised `app` container can read host metrics. This is the accepted design boundary."
- [ ] **No network change** — this AC documents the decision made by deferred-88

### **AC6: DiskDataVolumeHigh Alert — Volume Mount Dependency**
- [ ] **Current state:** Alert requires `mountpoint="/opt/skillars/data"` (`alerts.yml` lines ~62–68). Prometheus rule fires only if that mount is present.
- [ ] **Partial backstop:** `alerts.yml` also defines a root-filesystem `DiskHigh` alert on `mountpoint="/"` (lines ~82–90). If the Hetzner volume is **not** mounted, writes to `/opt/skillars/data` land on root (`/`), so the root-fs alert still catches disk exhaustion—without per-volume attribution.
- [ ] Add comment to `alerts.yml` near the volume alert (around line 62):
  ```yaml
  # DiskDataVolumeHigh requires the Hetzner volume to be mounted at /opt/skillars/data.
  # If unmounted, writes land on /, triggering DiskHigh instead.
  # See docs/deployment/first-time-setup.md for volume mount verification.
  ```
- [ ] Update `docs/deployment/first-time-setup.md` to include: "Verify Hetzner Volume is mounted at `/opt/skillars/data` before production deploy" (do not create a new prerequisites.md file)
- [ ] **No code change** — documentation only

### **AC7: Repo Clone Order — Document Fragility**
- [ ] Current state: repo is cloned to `/opt/skillars` before Hetzner Volume is mounted at `/opt/skillars/data` (benign today, fragile if repo structure changes)
- [ ] Add comment in `deploy/provision.sh` (around line 10–15, near the git clone): 
  ```
  # Clone repo BEFORE volume mount. This is benign because repo has no /data/ content.
  # If future changes add repo files under /data/, move this clone to AFTER volume mount.
  ```
- [ ] **No code change** — document only

### **AC8: Repo Cloned as Root — Design Limitation**
- [ ] Current state: `.git` sits alongside runtime data and secrets by design (pre-existing)
- [ ] Add comment to `deploy/provision.sh`: "Repo cloned as root into /opt/skillars. Runtime data is mounted at /opt/skillars/data. Separate deploy user or sparse-checkout is deferred as outside this story's scope."
- [ ] **No code change** — document the accepted limitation

### **AC9: git clean Safety — .gitignore Tightening**
- [ ] Current state: `.gitignore:86-89` now covers `/data/` explicitly; `git clean -fd` is safe
- [ ] Verify `.gitignore` carries the `/data/` entry with comment: "Hetzner Volume mount — do not clean"
- [ ] Add warning in docs: "`git clean -fdx` still deletes the entire production data tree (PostgreSQL, Redis, LGTM, Traefik); only use `git clean -fd`"
- [ ] **Verification:** `.gitignore` check passes; docs warning added to `docs/deployment/runbook.md`

### **AC10: PGPASSWORD in /proc — Project-Wide Pattern**
- [ ] Current state: credentials visible in `/proc/<pid>/environ` when `.env` is sourced (pre-existing, not introduced by this story)
- [ ] **Status:** Pre-existing pattern, out of this story's scope per specification
- [ ] **Action:** Defer to a separate platform-wide hardening story; do not attempt closure here

### **AC11: awscli v1 from Ubuntu apt — Deferred to Separate Story**
- [ ] **Current state:** `provision.sh:131` installs awscli v1 via Debian apt package
- [ ] **Issue:** Debian/Ubuntu do not package AWS CLI v2 in apt. The package `awscli` available via apt is v1.x only. AWS CLI v2 is distributed only via the official installer from `awscli.amazonaws.com`, not via PyPI or apt.
- [ ] **Attempt to install v2 via apt will fail:** `apt-get install -y "awscli=2.*"` returns *"Version '2.*' for 'awscli' was not found"* → `set -euo pipefail` abort → provisioning fails entirely.
- [ ] **Regression risk:** `pg-backup.sh` and `restore-from-dump.sh` contain logic written for awscli v1 semantics (multipart ETag handling, `multipart_threshold`). AWS CLI v2 enables a client-side pager by default that can cause hangs in non-TTY cron contexts without `AWS_PAGER=""` env var.
- [ ] **Action:** **Drop this AC from this story.** If v2 adoption is desired, create a separate story to:
  - Use the official AWS CLI v2 installer (curl → ./aws/install)
  - Pin the v2 version explicitly
  - Audit and update backup/restore scripts for v2 semantics
  - Set `AWS_PAGER=""` in all backup scripts
  - Test end-to-end on the provisioned environment
- [ ] **Current v1 is stable** — no action needed now

### **AC12: Deploy Workflow Notification Failures — Document and Fix**
- [ ] **Current state:** `.github/workflows/deploy.yml` has four notification steps (Slack & Email for success and failure) with **no `continue-on-error`**, and "Fail workflow" (line ~184) uses `if: steps.smoke.outputs.result == 'fail'` (implicit `success()`).
- [ ] **Actual issue:** When a real deploy failure occurs and then a notification step (e.g., "Notify Slack — failure & revert") errors:
  - The job **does go red** — the failure is visible
  - But subsequent steps (email notification, explicit failure marker) are **skipped** → failure email never sends and explicit marker never runs
- [ ] **Fix:** Add `continue-on-error: true` to the four notification steps (lines ~128, ~139, ~151, ~167), or use `if: always() && ...` to ensure notifications run even if smoke tests fail
- [ ] **Rationale:** Notification failures should not cascade. A delivery failure should not suppress the explicit failure marker.
- [ ] **Documentation:** Add comment at the Fail step: "Ensure all notification steps have `continue-on-error: true` so this marker always runs."
- [ ] **Verification:** Manually trigger a deploy that fails at smoke stage and verify: (1) notification steps execute, (2) job goes red, (3) explicit failure marker runs

### **AC13: Prometheus depends_on App — Deferred**
- [ ] **Current state:** `docker-compose.yml` Prometheus service (line ~220) has no `depends_on: app`; Grafana does.
- [ ] **Clarification:** `depends_on: [app]` (short form) means `condition: service_started` — it waits only for the app *container* to start, **not** for the healthcheck to pass. The app has `start_period: 60s` and healthcheck retries, so Prometheus starts before app is actually healthy, defeating the stated goal.
- [ ] **If "corrected" to `condition: service_healthy`:** This creates an operational **regression**. Grafana's `depends_on.prometheus` means the chain becomes: postgres/redis healthy → app healthy → prometheus → grafana. A crashing or misconfigured app then prevents Prometheus and Grafana from starting, losing metrics and alerting precisely when needed.
- [ ] **Risk assessment:** A target being briefly `up == 0` at cold start is normal Prometheus behavior; the next `scrape_interval` recovers it. There is no failure to eliminate.
- [ ] **Action:** **Drop this AC** — do not add `depends_on: app` to Prometheus. Monitoring must be independent of app health. If startup sequencing is desired, use the documented `condition: service_started` form with an explicit comment that it must **never** be tightened to `service_healthy`.

### **AC14: LGTM mkdir-p Gating**
- [ ] Current state: `provision.sh:594-603` has `mkdir -p` calls inside `[ -b "${VOLUME_DEVICE}" ]` check
- [ ] This is consistent with existing postgres pattern per the original review
- [ ] If volume is absent, Docker auto-creates dirs as root (compounds permission issue)
- [ ] **Status:** Accepted design; monitor for permission failures on new deployments
- [ ] Add comment: "mkdir-p calls are gated inside volume-device check. If volume is absent, Docker creates dirs as root; watch for permission errors on first provision."

### **AC15: VideoModerationEmailListener Blank Address — Analyze Tradeoff**
- [ ] **Current behavior:** `VideoModerationEmailListener.java` lines 46–57 check `platform.admin_alert_email` at `@PostConstruct`. If blank **and** `ARACHNID_ENABLED` is on, throws `IllegalStateException` and **aborts startup entirely**. If `ARACHNID_ENABLED` is off, continues silently.
- [ ] **At runtime:** `adminAlertEnvelope()` line 114 checks if blank again and logs ERROR: *"platform.admin_alert_email config key is blank — admin alert NOT sent"*. Then `sendAdminAlertSync()` returns normally.
- [ ] **Premise issue:** Story claims "no ERROR log" — but there IS one at line 115. Startup signal exists for the case that matters (CSAM detection enabled).
- [ ] **Design decision already made:** Lines 86–89 state: *"Returning normally is deliberate: no number of re-drives fixes an unset config key, and a retained row would occupy a claim slot until a human noticed."* Throwing would directly violate this.
- [ ] **Complication:** `adminAlertEnvelope()` helper (line 112) is called by both `sendAdminAlertSync()` (line 84) **and** `@EventListener onAdminAlert()` (line 61). Making it throw changes exception semantics for both paths — an exception out of `onAdminAlert()` propagates to whoever published the moderation event.
- [ ] **Test impact:** `VideoModerationEmailListenerTest.blankRecipient_returnsWithoutSending()` asserts no throw; story does not call out that this test would need to be inverted.
- [ ] **Action:** **Keep current design.** If a stronger signal is wanted, add a rate-limited WARN + a metric/counter in `adminAlertEnvelope()`, or gate the whole alert path on a "moderation configured" feature flag mirroring the existing `ARACHNID_ENABLED` guard. Do not throw.

### **AC16: Outbox Listener Branch Coverage — Verify, Do Not Extend**
- [ ] **Current state:** `VideoModerationEmailListenerTest` contains unit tests with mocked `MailManager` covering the branch logic:
  - `retryableFailure*` tests: `MailManager` throws retryable exception → listener logs as retryable → row retained
  - `permanentFailure*` tests: `MailManager` throws permanent exception → listener deletes row
  - `sentEnvelope*` test: success path
- [ ] **Real `MailManager` exception→flag mapping is already tested:** `MailManagerResilienceTest` pins the exact behavior (which exceptions map to `isRetry=true` vs false).
- [ ] **Potential gap:** The listener's claim/deletion semantics (framework actually retaining/deleting rows on exception) are **framework-dependent**, not listener logic. Both unit tests and `MailManagerResilienceTest` cover the listener's responsibility; the framework's claim behavior is assumed per the outbox pattern.
- [ ] **Integration test constraint:** Adding a real-mail IT that throws `MailException` requires mocking the mail service or using `@MockitoBean`, both of which fork the Spring context and trip `IntegrationTestConventionTest`. This is a documented blocking constraint.
- [ ] **Action:** Verify the existing unit test coverage is sufficient. If a residual gap is identified (which exact transition, and why existing tests don't cover it), file a separate story. Do not extend this AC into an impossible IT.

### **AC17: Payment Transactional Safety — @Transactional Audit**
- [ ] Current state: `SessionPackPaymentService.purchasePack()` is not annotated `@Transactional`
- [ ] **Issue:** Atomicity not guaranteed between Stripe charge and `SessionPackPurchase` persist
- [ ] **Decision Point:** Should we add `@Transactional`, or is the existing compensating-action pattern (manual refund on failure) sufficient?
- [ ] **Spec:** Current design uses compensating actions; leave as-is (per deferred-82/83 decisions)
- [ ] **Action:** Add javadoc comment: "purchasePack uses compensating-action pattern (charge → persist → on-failure refund). Not annotated @Transactional to avoid holding DB connection open across external Stripe call."
- [ ] **No code change required** — document only

---

## Scope After Code Review

This story contains 17 acceptance criteria across:
- **Code changes:** AC1 (PGPASSWORD pattern fix), AC12 (add `continue-on-error` to deploy workflow)
- **Documentation:** AC2–AC10, AC14, AC17 (UID comments, alert dependencies, design decisions)
- **Deferred/No change:** AC11 (awscli v2 → separate story), AC13 (monitoring regression → drop), AC15–AC16 (current design is sound → keep as-is)

The story remains substantial and deployable.

---

## Dev Notes

### Code Locations
- **Deploy scripts:** `deploy/backup/*.sh` (pg-backup.sh, restore-from-dump.sh, restore-from-volume-backup.sh, prune-backups.sh, volume-backup.sh)
- **Compose config:** `docker-compose.yml`
- **Provision:** `deploy/provision.sh`
- **Listener:** `src/main/java/com/softropic/skillars/platform/video/service/VideoModerationEmailListener.java`
- **Tests:** `src/test/java/.../VideoModerationEmailListenerTest.java`, `ModerationOutboxIT.java`
- **Docs:** `docs/deployment/backup-restore.md`, `docs/deployment/runbook.md`, `docs/deployment/first-time-setup.md`, `alerts.yml`

### Implementation Notes
- **AC1 (PGPASSWORD — Environment Inheritance Pattern):** All 5 occurrences (pg-backup.sh:32, restore-from-dump.sh:134/137/143/152):
  ```bash
  PGPASSWORD="${POSTGRES_PASSWORD}" docker exec -e PGPASSWORD "$APP_CID" psql -U postgres -h localhost ...
  ```
  - The bare `-e PGPASSWORD` (no `=value` suffix) tells Docker to copy the value from its own environment
  - The `PGPASSWORD=... cmd` prefix sets the env var, not argv — not visible in `ps aux`
  - Works with arbitrary password characters (unlike heredoc)
  - Verify post-fix: `grep -rE 'docker exec .*-e +PGPASSWORD=' deploy/` returns zero (grep for `=` suffix)

- **AC11 (awscli version):** **DROPPED** — AWS CLI v2 not available in Debian/Ubuntu apt; would require official installer. Defer to separate story. Current v1 is stable.

- **AC12 (Deploy Workflow Notifications):** Add `continue-on-error: true` to notification steps (lines ~128, ~139, ~151, ~167) in `.github/workflows/deploy.yml`
  - Ensures email and explicit failure marker run even if a notification step errors
  - Prevents cascading failures from hiding the deploy status

- **AC13 (Prometheus depends_on):** **DROPPED** — `depends_on: [app]` only waits for container start, not health. Changing to `service_healthy` creates regression (app crash → Prometheus never starts → lose monitoring). Current independent startup is correct.

- **AC15 (VideoModerationEmailListener):** **KEEP CURRENT DESIGN.** Blank `platform.admin_alert_email` already logs ERROR at line 115 and aborts startup if `ARACHNID_ENABLED` is on (lines 46–57). Do not throw — would occupy outbox slot and violate existing design decision.

### Testing Strategy
- **AC1:** Grep verification only; no functional test (credential exposure is "not happening" behavior). Verify with `grep -rE 'docker exec .*-e +PGPASSWORD=' deploy/` (checks for `=` suffix)
- **AC12:** Manual verification — trigger deploy failure and confirm: (1) notification steps execute, (2) job goes red, (3) explicit failure marker runs
- **AC15:** Verify no changes needed — existing unit tests and `@PostConstruct` check already provide coverage. Keep current behavior.
- **AC16:** Existing unit test coverage in `VideoModerationEmailListenerTest` and `MailManagerResilienceTest` is sufficient. Do not add new IT (blocked by convention test).

### Decisions Made (Corrected by Code Review, 2026-09-07)
1. **AC1 — PGPASSWORD pattern:** Environment variable inheritance (`PGPASSWORD="${POSTGRES_PASSWORD}" docker exec -e PGPASSWORD "$CID" psql ...`) — works with arbitrary characters, visible only in env not argv, no temp files
2. **AC11 — awscli version:** **DROPPED** — v2 not in Debian/Ubuntu apt. Current v1 stable. If v2 needed, requires separate story with official installer + v1-comment audit
3. **AC12 — Deploy notification failures:** Add `continue-on-error: true` to notification steps to prevent cascading failures
4. **AC13 — Prometheus depends_on:** **DROPPED** — `service_healthy` would cause operational regression (app crash → monitoring unavailable). Monitoring must be independent.
5. **AC15 — admin alert misconfiguration:** **KEEP CURRENT DESIGN** — already has ERROR log and startup abort for CSAM case. Do not throw; would exhaust outbox slots and violate existing decision documented in code

---

## Remaining Work After This Story

- **de-DE/fr-FR native-speaker verification** (carried forward, human review needed)
- **SLU non-gating perf-trend signal** (separate performance-tracking story)
- **Frontend test framework** (Vitest/Vue Test Utils setup, separate initiative)
- **Completion-gated coach payout** (AC5 Part B, separate payment-architecture story)
- **`deferred-work.md` / `sprint-status.yaml` line-length hygiene** (project-wide refactor, out of scope)
- All other deferred items in the ledger not covered by this story

---

## Story Completion Checklist
- [ ] All AC1–AC17 implemented or documented
- [ ] `deferred-work.md` updated: items closed/tagged as picked-up by this story
- [ ] `sprint-status.yaml` updated: story marked `done`
- [ ] Code review passed
- [ ] `mvn -o verify` green (backend ACs only; frontend is ESLint + build)
- [ ] No new items added to deferred ledger

---

## Review Findings

_bmad-code-review, 2026-09-07 — 3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor), no layer failures. Originally 1 decision-needed, 8 patch, 6 defer, 4 dismissed as noise. Decision resolved 2026-09-07 → accept as-is (dismissed), 1 new follow-up deferred._

### Decision needed — RESOLVED

- [x] [Review][Decision] AC12 residual — notification-delivery failures now conclude a deploy green with no alert — With `continue-on-error: true` on all four notify steps and no `id:`/`if: always()` surfacing step, a *successful* deploy whose Slack **and** email both fail to send still concludes green and is indistinguishable from a notified success; operators lose the only signal the notification path works. The spec deliberately chose `continue-on-error` (AC12, Decision #3). **RESOLVED 2026-09-07: accept as-is.** Verified that `continue-on-error: true` is scoped strictly to the four notification steps (`.github/workflows/deploy.yml` :130/:142/:154/:171) and does **not** touch `Deploy`, `Smoke test`, `Auto-Revert`, or the `Fail workflow` marker — non-notification errors still fail the run, so the trade-off is acceptable. Follow-up captured in Deferred: the actuator health endpoint should expose SMTP and Slack-webhook reachability so a down notification channel is visible independently of a deploy.

### Patch

_All 7 code/doc patches applied and re-verified 2026-09-07 (`bash -n` + `shellcheck -S warning` clean on provision.sh; `deploy.yml` / `alerts.yml` parse as YAML). Housekeeping item substantially done._

- [x] [Review][Patch] AC14 comment misplaced and self-contradictory [deploy/provision.sh:631] — **DONE.** Comment removed from the section 7.5 header; a corrected copy now sits at `provision.sh:603`, inside the `if [ -b "${VOLUME_DEVICE}" ]` block (opens :468, `else` :614), directly above `mkdir -p "${MOUNT_POINT}/postgres"`. Claim "gated inside the volume-device check" is now true for the block it annotates. The 7.5 header's redis/traefik relocation rationale is left intact.
- [x] [Review][Patch] alerts.yml comment names a non-existent alert [deploy/lgtm/alerts.yml:62] — **DONE.** Now reads: unmounted → `mountpoint="/opt/skillars/data"` metric absent and this alert silent; root-fs usage still fires `DiskRootHigh` (lines 83–98). Correct alert name; accurate "goes silent" wording instead of "hands off".
- [x] [Review][Patch] Imprecise volume-mount check in docs [docs/deployment/first-time-setup.md:377] — **DONE.** Replaced `mount | grep …` with `mountpoint -q /opt/skillars/data && echo 'mounted' || echo 'NOT mounted'` — definitive, usable exit code, no false match on `data-backup`.
- [x] [Review][Patch] `git clean -fd` called unconditionally "safe" [docs/deployment/runbook.md:489] — **DONE.** `-fd` now framed as "safer than `-fdx`, but still deletes untracked content (local artifacts, editor temp files, uncommitted scripts)"; `-fdX` (capital) added alongside `-fdx`.
- [x] [Review][Patch] Marker step uses fragile implicit `success()`; comment states wrong invariant [.github/workflows/deploy.yml:188] — **DONE.** `if:` changed to `always() && steps.smoke.outputs.result == 'fail'` (spec-sanctioned form); comment now states the real invariant ("no step between `smoke` and this marker may fail the job without `continue-on-error: true`") and keeps the :130/:142/:154/:171 references.
- [x] [Review][Patch] AC7 comment refers to a `git clone` that is not in this file [deploy/provision.sh:14] — **DONE.** Reworded to "repo is cloned manually in docs/deployment/first-time-setup.md before provision.sh runs … that manual clone step would need to move to AFTER provision.sh mounts the Volume." No longer implies an in-script clone.
- [x] [Review][Patch] AC17 javadoc overstates the refund guarantee [SessionPackPaymentService.java:57] — **DONE.** "charge → persist → on-failure best-effort refund" + explicit sentence: only `PaymentGatewayException` is caught, other exceptions propagate, refund failures are logged with no reconciliation record.
- [x] [Review][Patch] Story-completion housekeeping — **DONE (substantially).** `sprint-status.yaml` now carries a `skillars-deferred-94-…: done` entry; `deferred-work.md` carries 16 `[PICKED UP by skillars-deferred-94 AC…]` tags across the deploy/listener ledger plus this review's 6 defers + the health-endpoint follow-up. Nit: the sprint-status comment still says housekeeping is "in progress" — trailing wording only; the tagging is complete.

### Deferred (pre-existing, logged to deferred-work.md)

- [x] [Review][Defer] Smoke step *erroring* (vs. `result=fail`) → red run, no alert, no auto-revert [.github/workflows/deploy.yml:88] — deferred, pre-existing
- [x] [Review][Defer] Empty `APP_CID` fails a restore that actually succeeded; AC3 comment attributes it to the wrong cause; no bounded retry [deploy/backup/restore-from-dump.sh:197] — deferred, pre-existing (AC3 scoped doc-only)
- [x] [Review][Defer] `pg_dump | gzip > DUMP_FILE` runs before the cleanup `trap` is registered — a failed dump leaks a truncated file into `/tmp` [deploy/backup/pg-backup.sh:32] — deferred, pre-existing
- [x] [Review][Defer] `purchasePack` compensating refund is best-effort: non-`PaymentGatewayException` from `refund()` propagates; refund failures get no persisted reconciliation record [src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:92] — deferred, pre-existing
- [x] [Review][Defer] Hardcoded container-UID `chown` values are never asserted against the image's real runtime UID; a future image bump that shifts a UID yields a silently unwritable data dir [deploy/provision.sh:599] — deferred, pre-existing (new comment is the accepted mitigation)
- [x] [Review][Defer] Repo cloned as root into `/opt/skillars` alongside runtime data + secrets; one `git clean -fdx` wipes all production data — AC8 accepts as out-of-scope but no follow-up story is tracked [deploy/provision.sh:14] — deferred, pre-existing
- [x] [Review][Defer] Actuator health endpoint should expose SMTP + Slack-webhook reachability — new follow-up from the AC12 decision; a down notification channel must be visible independently of a deploy run (custom `HealthIndicator` for mail + Slack)

### Dismissed as noise

- PGPASSWORD env-passthrough "less robust under snap-confined docker / env-sanitizing wrapper" — `provision.sh:155` installs `docker-ce` via apt (not snap); the scripts set the var and call `docker` in the same shell with no intervening wrapper. Pattern is correct for the documented deployment and was a deliberate spec decision.
- `steps.revert.outputs.outcome` "renders empty, should be `steps.revert.outcome`" — false positive: the revert step writes `echo "outcome=..." >> $GITHUB_OUTPUT` in every branch, so `.outputs.outcome` resolves correctly.
- AC6 volume-mount check placed under "Step 7: Verify the Environment" vs. "before production deploy" — the first-time-setup verify step *is* before the production deploy; content satisfies intent.
- AC2 UID comment lists six services but only four are `chown`ed at that spot — the comment is a general statement about the script's `chown` calls, not a claim that all six are chowned inline; not worth a change.

---

## Related Deferred Work
- **Predecessor:** skillars-deferred-93 (OTP security + ledger prune)
- **Cross-references:** 
  - deferred-88 AC8 (node_exporter isolation, narrowed this story's AC5)
  - deferred-92 AC2–AC5 (outbox atomicity, partially addresses AC16)
  - deferred-82/83 (payment transactional decisions, frames AC17)
