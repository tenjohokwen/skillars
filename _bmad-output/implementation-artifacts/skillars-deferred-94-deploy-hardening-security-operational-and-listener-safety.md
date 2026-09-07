# Story: skillars-deferred-94-deploy-hardening-security-operational-and-listener-safety

## Story Identifier
- **Story Key:** skillars-deferred-94
- **Epic:** Deferred Work Backlog
- **Status:** ready-for-dev
- **Priority:** High (security + operational safety + listener reliability)

---

## Story Summary

A comprehensive hardening pass across deployment scripts, infrastructure security, transactional safety, and listener reliability. This story addresses 15+ deploy-* infrastructure gaps (PGPASSWORD exposure, hardcoded UIDs, network isolation, graceful handling of missing volumes) plus genuine one-off bugs in video moderation emails and payment semantics verification.

---

## User Story Statement

**As an** operations/platform team  
**I want** the deployment stack hardened against credential exposure, misconfigured containers, and silent listener failures  
**So that** production incidents are prevented, credential leaks are closed, and operational safety is strengthened

---

## Acceptance Criteria

### **AC1: PGPASSWORD Exposure — All 4 Occurrences Closed**
- [ ] `deploy/backup/pg-backup.sh` — line 32: Replace `docker exec -e PGPASSWORD="$DB_PASS"` with heredoc pattern
- [ ] `deploy/backup/restore-from-dump.sh` — lines 135, 138, 142: Same heredoc pattern applied to `DROP DATABASE`, `CREATE DATABASE`, and dump replay
- [ ] **Verification:** `grep -r "docker exec -e PGPASSWORD" deploy/` returns zero matches
- [ ] **Spec:** Use heredoc with process substitution: `docker exec -e PGPASSWORD="$(cat <<'PGPW'..."PGPW')" app psql ...`
  - Credentials never touch filesystem (no temp file)
  - Credentials never appear in `ps aux` (process argument, not env var visible to ps)
  - Most concise and secure pattern
- [ ] **Example pattern:**
  ```bash
  docker exec -e PGPASSWORD="$(cat <<'PGPW'
  $DB_PASS
  PGPW
  )" app psql -U postgres -h localhost -c "DROP DATABASE ..."
  ```

### **AC2: Hardcoded Container UIDs — Bind to Image Versions**
- [ ] Document the container UID assumptions (65534=nobody, 10001=postgres, 472=grafana) in `deploy/provision.sh` and `deploy/backup/restore-from-volume-backup.sh`
- [ ] Add a guard comment in both scripts: "Verify against upstream image changelogs before deploy to new host"
- [ ] Snapshot current base images in `.env.example` or `docs/deployment/prerequisites.md` with their known UIDs
- [ ] **Verification:** Comments added to lines ~597-603 (provision.sh) and ~96-99 (restore-from-volume-backup.sh) naming the specific images and their UIDs

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
- [ ] Current state: alert requires `mountpoint="/opt/skillars/data"` (`alerts.yml:62-68`)
- [ ] Add verification step to `docs/deployment/prerequisites.md` or `deploy/provision.sh` comment:
  - "Verify Hetzner Volume is mounted at `/opt/skillars/data` before deployment or alert will never fire"
  - "If volume is unmounted, `DiskDataVolumeHigh` will silently never trigger"
- [ ] **No code change** — document the operational prerequisite

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

### **AC11: awscli v1 from Ubuntu apt — Pin to v2**
- [ ] Current state: `provision.sh:131` installs awscli v1 which has edge cases with Hetzner Object Storage
- [ ] **Change:** Pin to awscli v2 in `provision.sh` near line 131
  ```bash
  # Before:
  apt-get install -y awscli
  
  # After:
  apt-get install -y awscli=2.* python3-pip
  ```
- [ ] **Rationale:** v2 is actively maintained, fewer Hetzner edge cases, recommended upstream
- [ ] **Verification:** `aws --version` in provisioned environment shows `aws-cli/2.x.x`
- [ ] **No docs change** — v2 is the stable choice going forward

### **AC12: Fail Workflow Citation Update**
- [ ] Current state: `.github/workflows/deploy.yml:184-188` has become the "Fail workflow" step (previously `:139-143`)
- [ ] Four notification steps (Slack/Email success+failure) precede it, so any notification failure pre-empts the explicit failure marker
- [ ] Update the deferred-work.md entry (already done by the 2026-09-04 audit) to reflect current line numbers
- [ ] Add a comment in `deploy.yml` at the Fail step: "This step is unreachable if any notification step throws. Notification failures are logged at WARN but may hide the true deploy failure."
- [ ] **No code change** — document the architectural limitation

### **AC13: Prometheus depends_on App — Add Dependency**
- [ ] Current state: `docker-compose.yml:217-235` (Prometheus) has no `depends_on: app`; Grafana at `:328` does
- [ ] **Change:** Add `depends_on: - app` to the Prometheus service definition in docker-compose.yml
  ```yaml
  prometheus:
    image: prom/prometheus:latest
    depends_on:
      - app    # Ensure app is healthy before Prometheus starts scraping
    ports: [...]
  ```
- [ ] **Rationale:** Eliminates cold-start scrape failures; ensures Prometheus only scrapes once app is ready
- [ ] **Verification:** On fresh `docker compose up`, verify Prometheus enters `healthy` state after app reaches `healthy`
- [ ] **No regression risk:** Only makes startup order explicit; no behavioral change for running deployments

### **AC14: LGTM mkdir-p Gating**
- [ ] Current state: `provision.sh:594-603` has `mkdir -p` calls inside `[ -b "${VOLUME_DEVICE}" ]` check
- [ ] This is consistent with existing postgres pattern per the original review
- [ ] If volume is absent, Docker auto-creates dirs as root (compounds permission issue)
- [ ] **Status:** Accepted design; monitor for permission failures on new deployments
- [ ] Add comment: "mkdir-p calls are gated inside volume-device check. If volume is absent, Docker creates dirs as root; watch for permission errors on first provision."

### **AC15: VideoModerationEmailListener Fail-Open Path — Throw on Misconfigured Address**
- [ ] Current state: When `platform.admin_alert_email` is blank, `VideoModerationEmailListener.sendAdminAlertSync()` returns normally without sending
- [ ] **Issue:** No ERROR log; outbox row is released silently; operator has no signal that admin alerts are disabled
- [ ] **Fix:** Change blank-recipient case to throw `ConfigurationException` so listener framework retains the outbox row
  ```java
  // In VideoModerationEmailListener.sendAdminAlertSync():
  String adminAlertEmail = config.getString("platform.admin_alert_email", "");
  if (adminAlertEmail.isBlank()) {
    throw new ConfigurationException(
      "platform.admin_alert_email is not set; video moderation admin alerts cannot be sent. " +
      "Set this config key and restart to enable admin alert emails."
    );
  }
  // ... continue with send
  ```
- [ ] **Acceptance:** `VideoModerationEmailListener.java` throws on blank recipient; Spring listener logs exception at ERROR
- [ ] **Verification:** 
  - `VideoModerationEmailListenerTest` asserts `ConfigurationException` is thrown when admin alert is unset
  - Outbox row is retained (Spring listener framework does not delete on exception)
  - Operator sees ERROR in logs with clear diagnostic message
- [ ] **Spec:** Throw early with clear message; let Spring listener framework handle retry + retention

### **AC16: AC6 Outbox Retain/Delete Semantics — End-to-End Test**
- [ ] Current state: `VideoModerationEmailListenerTest:150-173` uses mocked `JavaMailSender`; never exercises real `MailManager`
- [ ] **Gap:** Branch logic is covered (retryable vs permanent failure) but E2E mapping from exception → `EnvelopeEntity(FAILED, isRetry)` is never tested
- [ ] **Fix:** Add or update `ModerationOutboxIT` (or equivalent real-mail IT) to drive both paths:
  - Retryable failure (e.g., `SmtpException`) → row retained with `isRetry=true`
  - Permanent failure (e.g., invalid address) → row deleted
- [ ] **Verification:** End-to-end test passes for both failure modes; mutations on the retention logic fail the test

### **AC17: Payment Transactional Safety — @Transactional Audit**
- [ ] Current state: `SessionPackPaymentService.purchasePack()` is not annotated `@Transactional`
- [ ] **Issue:** Atomicity not guaranteed between Stripe charge and `SessionPackPurchase` persist
- [ ] **Decision Point:** Should we add `@Transactional`, or is the existing compensating-action pattern (manual refund on failure) sufficient?
- [ ] **Spec:** Current design uses compensating actions; leave as-is (per deferred-82/83 decisions)
- [ ] **Action:** Add javadoc comment: "purchasePack uses compensating-action pattern (charge → persist → on-failure refund). Not annotated @Transactional to avoid holding DB connection open across external Stripe call."
- [ ] **No code change required** — document only

---

## Priority: Deploy-* Items Only vs. With Genuine Bugs

This story **combines both**: all 14 deploy-* security/operational gaps **plus** 3 genuine one-off bugs (AC15–AC17 above). This makes it substantial and shippable without requiring a separate story.

---

## Dev Notes

### Code Locations
- **Deploy scripts:** `deploy/backup/*.sh` (pg-backup.sh, restore-from-dump.sh, restore-from-volume-backup.sh, prune-backups.sh, volume-backup.sh)
- **Compose config:** `docker-compose.yml`
- **Provision:** `deploy/provision.sh`
- **Listener:** `src/main/java/com/softropic/skillars/platform/video/service/VideoModerationEmailListener.java`
- **Tests:** `src/test/java/.../VideoModerationEmailListenerTest.java`, `ModerationOutboxIT.java`
- **Docs:** `docs/deployment/backup-restore.md`, `docs/deployment/runbook.md`, `docs/deployment/prerequisites.md`

### Implementation Notes
- **AC1 (PGPASSWORD — Heredoc pattern):** All four occurrences (pg-backup.sh:32, restore-from-dump.sh:135/138/142) use the same pattern:
  ```bash
  DB_PASS="..."  # from .env or config
  docker exec -e PGPASSWORD="$(cat <<'PGPW'
  $DB_PASS
  PGPW
  )" app psql -U postgres -h localhost ...
  ```
  - Process substitution keeps credentials in-memory only
  - Credentials never appear in `ps aux` or temp files
  - Verify post-fix: `grep -r "docker exec -e PGPASSWORD" deploy/` returns zero

- **AC11 (awscli v2 pin):** Update `provision.sh:131` from `apt-get install -y awscli` to `apt-get install -y awscli=2.*`
  - Ensures consistency across all provisioning
  - Verify: `aws --version` shows `aws-cli/2.x.x`

- **AC13 (Prometheus depends_on):** In `docker-compose.yml:217`, add:
  ```yaml
  depends_on:
    - app
  ```
  - Ensures app is healthy before Prometheus starts scraping
  - No behavior change; only startup order

- **AC15–AC16 (VideoModerationEmailListener):** 
  - `VideoModerationEmailListener.sendAdminAlertSync()` checks if `platform.admin_alert_email` is blank
  - If blank, throw `ConfigurationException` with clear message (do NOT return silently)
  - Spring `@TransactionalEventListener` catches exception, logs ERROR, and retains outbox row
  - `VideoModerationEmailListenerTest` asserts exception is thrown (no mock swallow)
  - Real failure modes: `MailManager` returns retryable vs permanent → `EnvelopeEntity(FAILED, isRetry)` flag controls retention

### Testing Strategy
- **AC1:** Grep verification only; no functional test (credential exposure is "not happening" behavior)
- **AC15:** Add or update `VideoModerationEmailListenerTest` to assert `ConfigurationException` is thrown on blank recipient
- **AC16:** Real integration test with `MailManager` mock that simulates retryable and permanent failures

### Decisions Made (By User, 2026-09-05)
1. **AC1 — PGPASSWORD pattern:** Heredoc with process substitution (`$(cat <<'PW'...'PW')` pattern) — most secure, no temp files
2. **AC11 — awscli version:** Pin to v2 now (`apt-get install -y awscli=2.*`) — modern, fewer edge cases
3. **AC13 — Prometheus depends_on:** Add `depends_on: - app` — eliminates cold-start scrape failures
4. **AC15 — admin alert misconfiguration:** Throw `ConfigurationException` on blank address — clear ERROR in logs, outbox retained by framework

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

## Related Deferred Work
- **Predecessor:** skillars-deferred-93 (OTP security + ledger prune)
- **Cross-references:** 
  - deferred-88 AC8 (node_exporter isolation, narrowed this story's AC5)
  - deferred-92 AC2–AC5 (outbox atomicity, partially addresses AC16)
  - deferred-82/83 (payment transactional decisions, frames AC17)
