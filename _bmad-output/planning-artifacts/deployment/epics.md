---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - '_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/prd.md'
  - '_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/addendum.md'
  - '_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/reconcile-deployment-proposal.md'
---

# javatemplate - Deployment & Infrastructure Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for the Deployment & Infrastructure module, decomposing the requirements from the PRD, Addendum, and Reconciliation Report into implementable stories. No formal Architecture document exists for this module — technical decisions are captured directly in the PRD and Addendum.

## Requirements Inventory

### Functional Requirements

**FR-1:** A developer can provision a fresh Node to a production-ready state by following a single documented procedure (script or runbook) that installs all required system dependencies, configures SSH hardening, installs Docker and Docker Compose, and prepares the directory structure for secrets and application data. The procedure completes without the developer looking outside `docs/deployment` and within the ≤ 2-hour first-time setup target.

**FR-2:** Hetzner Cloud Firewall rules for the Node are defined and applied via IaC (not manual UI). Inbound TCP 80 and 443 are open from all sources. Inbound TCP 22 is restricted to allowlisted IP(s) only. All other inbound traffic is blocked. Firewall state can be reproduced by running the IaC tooling against the Hetzner API.

**FR-3:** First-time setup documentation covers: domain purchase, how to create a DNS A record pointing to the Node IP, how to verify DNS propagation, and how long to wait before troubleshooting TLS issuance. A developer with no prior context can complete DNS configuration using the documentation alone.

**FR-4:** On every merge to `main`, the pipeline automatically builds an immutable Docker image tagged with the commit SHA and pushes it to GHCR. Every merged commit produces a corresponding tagged image. The image tag is deterministic and traceable to its source commit. Build failures block the pipeline.

**FR-5:** A developer can trigger a Production Deploy via the GitHub Actions UI by selecting the target image tag — no CLI access or direct SSH required to initiate. The workflow connects to the Node via SSH, pulls the specified image using `docker compose pull <service> && docker compose up -d --no-deps <service>`, and restarts the affected service with no additional manual steps on the server.

**FR-6:** After a Production Deploy, the pipeline runs a Smoke Test against `/actuator/health` within 60 seconds of service restart. A failed Smoke Test triggers Auto-Revert: the workflow captures the current running image digest from `docker inspect` before deploying, and if the test fails, re-pins that digest and restarts services. The deployer receives an alert via email and Slack on both successful deploy and Auto-Revert. A failed Smoke Test marks the build as failed in GitHub Actions.

**FR-7:** Every service (Application, PostgreSQL, Redis, Traefik, LGTM Stack) is defined in `docker-compose.yml` with explicit CPU and memory limits, a health check, and `restart: unless-stopped`. No service can consume unbounded CPU or memory. Traefik routes traffic only to containers whose health check reports healthy. Any service that crashes restarts automatically without operator intervention.

**FR-8:** Traefik obtains and renews TLS certificates via Let's Encrypt. The developer provides only the domain name; certificate issuance and renewal require no further manual action. The application is reachable over HTTPS at the configured domain with a valid certificate after first-time setup.

**FR-9:** All services communicate over a named internal bridge network. Only Traefik is bound to host ports 80 and 443. No other service (PostgreSQL, Redis, LGTM Stack) exposes ports to the host network or is reachable from outside the Node directly.

**FR-10:** Services define `stop_grace_period: 30s` in `docker-compose.yml` to allow in-flight requests to complete before container termination during a Production Deploy. Expected deploy downtime is ≤ 10 seconds under normal load.

**FR-11:** The Node accepts SSH connections via key-based authentication only. Password-based login is disabled. `fail2ban` is installed and configured with a block policy for repeated failed authentication attempts.

**FR-12:** All secrets are injected at runtime: GitHub Actions secrets for CI/CD, and a server `.env` file (mode `600`, owner read/write only) stored outside the repository root for the Node. No secrets appear in any committed file. The `.env` file is listed in `.gitignore`. A full scan of the repository history contains no secrets.

**FR-13:** The Traefik management dashboard is disabled in production or protected with BasicAuth middleware. An unauthenticated request to the Traefik dashboard endpoint returns 401 or a connection refusal.

**FR-14:** The Hetzner Volume is snapshotted daily via the Hetzner API. At least one volume snapshot is available at any point within the past 24 hours. Snapshot creation is automated, not manually triggered.

**FR-15:** `pg_dump` runs every 6 hours and pushes the dump to Hetzner Object Storage (not to the Node's local disk). At least one dump is available at any point within the past 6 hours.

**FR-16:** The full restore process — from snapshot or `pg_dump` to a running application — is implemented as an executable script (not merely a runbook) stored in the repository and documented in `docs/deployment`. The restore script is tested quarterly via a drill on a non-production environment; results are recorded.

**FR-17:** Prometheus, Grafana, Loki, and Tempo are deployed on the Node as part of the standard Compose stack with resource limits, health checks, and restart policies consistent with FR-7. Loki log retention is set to 30 days. Prometheus metrics retention is set to 15 days. Both limits are enforced in version-controlled configuration. Application logs are queryable in Loki and metrics are visible in Grafana dashboards.

**FR-18:** An external uptime monitor independent of the Node pings `/actuator/health` at a regular interval and alerts when the endpoint is unreachable. An alert fires within 5 minutes of the health endpoint becoming unreachable from outside the Node — including when the entire Node (LGTM stack included) is offline.

**FR-19:** Alert rules for disk usage, memory, and application health are defined in `alerts.yml` (version-controlled) and routed to email and Slack. Alerts fire at configured thresholds without operator intervention. Rules are not configured manually in the Grafana UI only.

**FR-20:** The Docker logging driver is configured with `max-size: "10m"` and `max-file: "3"` to prevent log files from exhausting local disk. Log files for any single container do not exceed the configured maximum size. Old log files are rotated automatically.

**FR-21:** A first-time setup guide in `docs/deployment` covers the complete path: domain purchase and DNS configuration, server provisioning, secrets placement, initial deploy, and verification. A developer following the guide on a fresh Node reaches a live, healthy, TLS-enabled environment within the ≤ 2-hour target without external assistance.

**FR-22:** An ongoing deployment guide in `docs/deployment` covers the regular deploy cycle: triggering the CI build, pulling to the UAT Server for manual validation, triggering the production deploy workflow, and interpreting the result. A developer following the guide completes a production deploy within the ≤ 30-minute target.

**FR-23:** A manual rollback procedure is documented covering: how to identify the previous image digest in GHCR, re-pin it in the deployment configuration, and restart the affected service — for cases requiring manual intervention beyond Auto-Revert.

**FR-24:** A backup and restore guide in `docs/deployment` covers how to initiate a restore from a volume snapshot or `pg_dump`, how to verify data integrity after restore (e.g., row count check, health endpoint passes), and how to bring the application back online.

**FR-25:** A secrets reference lists every required secret, its format, where it must be placed (GitHub Actions secrets vs. server `.env`), and how to generate or obtain it. No secret value is included. A developer who has never seen the project can locate and place every secret using the reference alone.

**FR-26:** A Traefik and TLS reference covers how Traefik is configured, how TLS certificates are issued and renewed, the expected renewal timeline, and what to do if a certificate fails to renew.

**FR-27:** A monitoring reference explains how to access Grafana, what dashboards exist, what each alert means, and what action to take when an alert fires. Every configured alert has a corresponding documented response action.

**FR-28:** An operational runbook covers three failure scenarios: disk exhaustion on the Node, PostgreSQL service down, and Redis OOM. Each scenario includes: a detection method, step-by-step remediation, and a verification check. A developer can resolve each scenario by following the runbook alone.

---

### Non-Functional Requirements

**NFR-1 (Configuration as Code):** All infrastructure state is version-controlled. No production configuration exists only in a UI or applied manually after initial provisioning.

**NFR-2 (Self-Contained Documentation):** Every procedure in `docs/deployment` is complete without external references. A developer following any guide should never need to open a tab outside the repository.

**NFR-3 (Idempotency):** Scripts must be safe to re-run without causing unintended side effects where technically feasible.

**NFR-4 (Resource Bounds):** No container may run without explicit CPU and memory limits defined in `docker-compose.yml`.

**NFR-5 (Deploy Downtime Budget):** In-place container restart produces ≤ 10 seconds of downtime. Acceptable at < 50 concurrent users.

**NFR-6 (Secrets Hygiene):** No secret ever appears in a committed file, a log output, or a CI output visible in GitHub Actions.

**NFR-7 (RTO):** Recovery Time Objective < 4 hours from failure detection to restored service.

**NFR-8 (RPO):** Recovery Point Objective < 24 hours via volume snapshot; < 6 hours via PostgreSQL logical backup.

**NFR-9 (Restore Validation):** Quarterly drill on a non-production environment; results recorded.

**NFR-10 (Log Retention):** Loki retains logs 30 days; Prometheus retains metrics 15 days. Limits enforced in version-controlled configuration.

**NFR-11 (Uptime Alerting):** External monitor alerts within 5 minutes of health endpoint failure.

**NFR-12 (Cost Constraint):** Infrastructure cost approximately €18/month for initial production scale (< 50 concurrent users): CX32 €7, 100GB Volume €5, Volume Snapshots €3, Object Storage €3.

---

### Additional Requirements

*(From Addendum and Reconciliation Report — concrete implementation values that must appear as testable consequences)*

- **Deploy command pattern:** SSH deploy step MUST use `docker compose pull <service> && docker compose up -d --no-deps <service>` — not a generic restart mechanism.
- **Health endpoint path:** Smoke Test and external uptime monitor MUST target `/actuator/health` specifically — not a generic path.
- **Grace period value:** `stop_grace_period: 30s` MUST be set in `docker-compose.yml` — not an unspecified grace period.
- **Log rotation values:** `max-size: "10m"` and `max-file: "3"` MUST be the configured limits — not arbitrary values.
- **Secrets file permission:** Server `.env` file MUST have Unix permission mode `600` — this is a testable security consequence of FR-12.
- **Auto-Revert depth:** Auto-Revert works one level back only (digest captured at deploy time via `docker inspect`); deeper rollbacks require the manual rollback procedure (FR-23).
- **Restore script:** FR-16 requires an executable script in the repository — a prose runbook alone does not satisfy the requirement.
- **Firewall IaC:** FR-2 requires an IaC tool (e.g., Terraform or hcloud CLI scripting) — manual Hetzner UI configuration does not satisfy the requirement.
- **Scaling trigger:** When traffic approaches 150–200 concurrent users, re-evaluate: detach DB to dedicated CX42 node, add second CX32 app node + Hetzner Load Balancer, isolate LGTM stack to dedicated small node.

---

### UX Design Requirements

*Not applicable — this module is infrastructure and CI/CD with no user-facing frontend.*

---

### FR Coverage Map

| FR | Epic | Description |
|---|---|---|
| FR-1 | Epic 1 | Server provisioning script/runbook |
| FR-2 | Epic 1 | IaC firewall (Hetzner Cloud) |
| FR-3 | Epic 1 | DNS setup documentation |
| FR-4 | Epic 2 | Automated image build + GHCR push on merge |
| FR-5 | Epic 2 | Manual production deploy trigger via GitHub Actions |
| FR-6 | Epic 2 | Smoke Test + Auto-Revert mechanism |
| FR-7 | Epic 1 | docker-compose.yml with resource limits + health checks |
| FR-8 | Epic 1 | Traefik TLS via Let's Encrypt |
| FR-9 | Epic 1 | Docker network isolation |
| FR-10 | Epic 1 | Graceful shutdown (`stop_grace_period: 30s`) |
| FR-11 | Epic 1 | SSH key-only auth + fail2ban |
| FR-12 | Epic 1 | Secrets management (GitHub Actions + server .env mode 600) |
| FR-13 | Epic 1 | Traefik dashboard locked |
| FR-14 | Epic 3 | Daily volume snapshots |
| FR-15 | Epic 3 | 6-hourly pg_dump to Object Storage |
| FR-16 | Epic 3 | Scripted restore + quarterly drill |
| FR-17 | Epic 1 | LGTM stack in docker-compose.yml |
| FR-18 | Epic 3 | External uptime monitoring + alerting |
| FR-19 | Epic 3 | Version-controlled alert rules (alerts.yml) |
| FR-20 | Epic 1 | Docker log rotation (10m/3) |
| FR-21 | Epic 1 | First-time setup guide (docs/deployment) |
| FR-22 | Epic 2 | Ongoing deployment guide |
| FR-23 | Epic 2 | Manual rollback procedure |
| FR-24 | Epic 3 | Backup and restore guide |
| FR-25 | Epic 1 | Secrets reference |
| FR-26 | Epic 3 | Traefik and TLS reference |
| FR-27 | Epic 3 | Monitoring reference |
| FR-28 | Epic 3 | Operational runbook |

## Epic List

### Epic 1: Production Server Foundation

A developer can provision a secure, fully configured production server, start all application services with resource limits, TLS, network isolation, and integrated observability, then verify the environment is ready to receive deployments — using only `docs/deployment` with no external assistance.

**FRs covered:** FR-1, FR-2, FR-3, FR-7, FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, FR-17, FR-20, FR-21, FR-25

### Epic 2: CI/CD Pipeline & Release Workflow

A developer can ship application releases to production on demand using a semi-automated pipeline — automated build on merge, manual production deploy trigger via GitHub Actions, Smoke Test with automatic rollback on failure — completing the full deploy cycle in ≤ 30 minutes.

**FRs covered:** FR-4, FR-5, FR-6, FR-22, FR-23

### Epic 3: Backup, Recovery & Operational Readiness

A developer can monitor application health in production, recover from any failure scenario within RTO/RPO targets using scripted procedures, respond to any operational alert, and handle common failure incidents — all using self-contained documentation without improvising steps.

**FRs covered:** FR-14, FR-15, FR-16, FR-18, FR-19, FR-24, FR-26, FR-27, FR-28

---

## Epic 1: Production Server Foundation

A developer can provision a secure, fully configured production server, start all application services with resource limits, TLS, network isolation, and integrated observability, then verify the environment is ready to receive deployments — using only `docs/deployment` with no external assistance.

### Story 1.1: Server Provisioning Script & IaC Firewall

As a developer inheriting the project with a fresh Hetzner CX32 node,
I want an executable provisioning script (or minimal-step runbook) and a version-controlled Hetzner Cloud Firewall definition,
So that I can take a raw server to a Docker-ready, network-secured state without improvising and without firewall configuration ever drifting from the intended state.

**Acceptance Criteria:**

**Given** a fresh Hetzner CX32 node,
**When** a developer runs the provisioning script / follows the runbook,
**Then** all required system dependencies are installed, SSH hardening is applied (key-only, fail2ban configured), Docker and Docker Compose are installed, and the directory structure for secrets and application data is prepared.
**And** the developer has not needed to consult any source outside `docs/deployment`.
**And** the script/runbook is idempotent — re-running it on an already-provisioned node does not cause unintended side effects.

**Given** the IaC firewall configuration is applied via the Hetzner API tooling,
**When** the Node's firewall state is inspected,
**Then** inbound TCP 80 and 443 are open from all sources.
**And** inbound TCP 22 is restricted to the configured allowlisted IP(s) only.
**And** all other inbound traffic is blocked.
**And** the firewall state can be fully reproduced by running the IaC tooling — manual Hetzner UI changes are not the authoritative source of truth.

---

### Story 1.2: Core Docker Compose Service Stack

As a developer,
I want all application services defined in `docker-compose.yml` with resource limits, health checks, restart policies, network isolation, Traefik TLS termination, graceful shutdown, and Docker log rotation,
So that I can start the entire production stack with a single `docker compose up -d` and no service can consume unbounded resources or be reached directly from outside the Node.

**Acceptance Criteria:**

**Given** a Node with Docker and Docker Compose installed,
**When** `docker compose up -d` is executed,
**Then** the Application, PostgreSQL, Redis, and Traefik services all start healthy.
**And** every service defines explicit CPU and memory limits, a health check, and `restart: unless-stopped`.
**And** every service defines `stop_grace_period: 30s`.
**And** Traefik binds exclusively to host ports 80 and 443 — no other service exposes ports to the host network.
**And** Application, PostgreSQL, and Redis communicate over a named internal bridge network; their ports are unreachable from outside the Node.
**And** Traefik routes traffic only to containers whose health check reports healthy.
**And** the Docker logging driver is configured globally with `max-size: "10m"` and `max-file: "3"`.

**Given** Traefik is running and a domain name is configured,
**When** a request is made to the domain over HTTPS,
**Then** Traefik returns a valid Let's Encrypt TLS certificate and the application responds.
**And** certificate renewal is fully automatic — no manual action required.

**Given** any service crashes on the Node,
**When** Docker detects the container has exited,
**Then** the service restarts automatically without operator intervention.

---

### Story 1.3: LGTM Observability Stack

As a developer,
I want Prometheus, Grafana, Loki, and Tempo added to the Docker Compose stack with version-controlled retention configuration and resource limits,
So that application metrics and logs are queryable from day one without disk exhaustion risk.

**Acceptance Criteria:**

**Given** the LGTM services are added to `docker-compose.yml`,
**When** `docker compose up -d` is executed,
**Then** Prometheus, Grafana, Loki, and Tempo start healthy with explicit resource limits, health checks, and `restart: unless-stopped`.
**And** Loki is configured with a 30-day log retention limit defined in version-controlled configuration (not set manually in any UI).
**And** Prometheus is configured with a 15-day metrics retention limit, also in version-controlled configuration.

**Given** the application is running and emitting logs and metrics,
**When** a developer opens Grafana,
**Then** application logs are queryable via the Loki data source.
**And** application metrics are visible in at least one Grafana dashboard.

**Given** the LGTM stack is running,
**When** the Node's network exposure is inspected,
**Then** no raw LGTM service port (Prometheus scrape port, Loki ingest port, Tempo port) is exposed on the host network — external access goes only through Traefik with authentication.

---

### Story 1.4: Security Hardening

As a developer,
I want the Node configured with SSH key-only authentication and `fail2ban`, all secrets injected at runtime with no committed values, the server `.env` file permissions enforced at mode `600`, and the Traefik dashboard inaccessible without authentication,
So that the Node is protected against unauthorized access and no secret can leak through committed files, logs, or CI output.

**Acceptance Criteria:**

**Given** the Node has been hardened,
**When** an SSH connection is attempted using a password,
**Then** the connection is rejected — only key-based authentication is accepted.
**And** `fail2ban` is active with a configured block policy for repeated failed authentication attempts.

**Given** the secrets configuration is in place,
**When** the full repository history is scanned,
**Then** no secret value appears in any committed file.
**And** the server `.env` file is listed in `.gitignore`.

**Given** the `.env` file has been placed on the Node,
**When** its Unix permissions are inspected,
**Then** the file has mode `600` (owner read/write only) and is stored outside the repository root directory.

**Given** Traefik is running in production configuration,
**When** an unauthenticated HTTP request is made to the Traefik dashboard endpoint,
**Then** the response is `401` or a connection refusal — the dashboard is not accessible without authentication (or is disabled entirely).

---

### Story 1.5: First-Time Setup Documentation

As a developer arriving at the project for the first time with SSH access, a secrets file, and a domain name,
I want a self-contained first-time setup guide, a complete secrets reference, and DNS configuration instructions all in `docs/deployment`,
So that I can reach a live, TLS-enabled production environment within 2 hours without asking anyone for help.

**Acceptance Criteria:**

**Given** `docs/deployment/first-time-setup.md` exists,
**When** a developer with zero prior context follows it end-to-end (domain, DNS, provisioning, secrets placement, initial deploy, verification),
**Then** the application is reachable over HTTPS at the configured domain with a valid certificate and a passing `/actuator/health` response — within the ≤ 2-hour target.
**And** no step requires the developer to open any resource outside the repository.

**Given** `docs/deployment/first-time-setup.md` includes DNS configuration,
**When** the developer follows the DNS section,
**Then** they can configure a domain A record pointing to the Node IP, verify DNS propagation, and understand how long to wait before troubleshooting TLS issuance — using only the guide.

**Given** `docs/deployment/secrets-reference.md` exists,
**When** a developer with no prior context reads it,
**Then** every secret required by the application and CI/CD pipeline is listed with its name, format, placement (GitHub Actions secrets vs. server `.env`), and how to generate or obtain it.
**And** no secret value appears anywhere in the document.

---

## Epic 2: CI/CD Pipeline & Release Workflow

A developer can ship application releases to production on demand using a semi-automated pipeline — automated build on merge, manual production deploy trigger via GitHub Actions, Smoke Test with automatic rollback on failure — completing the full deploy cycle in ≤ 30 minutes.

### Story 2.1: Automated CI Build Pipeline

As a developer who has merged a change to `main`,
I want the pipeline to automatically build an immutable Docker image tagged with the commit SHA and push it to GHCR,
So that every merged commit produces a traceable, deployable artifact without any manual build step.

**Acceptance Criteria:**

**Given** a pull request is merged to `main`,
**When** the GitHub Actions CI workflow runs,
**Then** a Docker image is built and pushed to GHCR tagged with the commit SHA.
**And** the image tag is deterministic — the same commit always produces the same tag.
**And** the image is traceable to its source commit via the tag.

**Given** the CI workflow runs,
**When** the Docker build step fails,
**Then** the workflow is marked as failed in GitHub Actions and no image is pushed to GHCR.
**And** the failure is reported in the GitHub Actions UI without requiring the developer to inspect logs manually.

**Given** secrets are required for GHCR authentication,
**When** the workflow pushes to GHCR,
**Then** registry credentials are read from GitHub Actions secrets — no credentials appear in any committed workflow file or CI output log.

---

### Story 2.2: Manual Production Deploy Workflow with Smoke Test & Auto-Revert

As a developer who has validated a release on the UAT server,
I want to trigger a production deploy via the GitHub Actions UI by selecting the target image tag — and have the pipeline automatically run a Smoke Test, alert me on the result, and revert to the previous image if the test fails,
So that I can ship confidently knowing production is always left in a known-good state.

**Acceptance Criteria:**

**Given** the production deploy workflow exists,
**When** a developer navigates to GitHub Actions,
**Then** the workflow is triggerable via `workflow_dispatch` with the target GHCR image tag as an explicit input — no CLI access or direct SSH to the Node is required to initiate the deploy.

**Given** a deploy is triggered with a valid image tag,
**When** the workflow runs,
**Then** it connects to the Node via SSH and executes `docker compose pull <service> && docker compose up -d --no-deps <service>` with the specified image.
**And** the workflow records the current running image digest from `docker inspect` before pulling the new image.

**Given** the new image has been started,
**When** the Smoke Test runs,
**Then** it queries `/actuator/health` within 60 seconds of the service restart.
**And** a healthy response marks the deploy as successful and sends an alert to the deployer via email and Slack.

**Given** the Smoke Test returns an unhealthy response or times out,
**When** Auto-Revert is triggered,
**Then** the workflow re-pins the previously recorded image digest and restarts the service.
**And** the deployer receives an alert via email and Slack identifying the deploy as failed and reverted.
**And** the workflow run is marked as failed in GitHub Actions.

---

### Story 2.3: Deployment & Rollback Documentation

As a developer who needs to ship a release or recover from a failed deploy,
I want an ongoing deployment guide and a manual rollback procedure in `docs/deployment`,
So that I can complete the full deploy cycle in ≤ 30 minutes and manually revert production to any previous image without improvising steps.

**Acceptance Criteria:**

**Given** `docs/deployment/deploy-guide.md` exists,
**When** a developer follows it for a regular deploy (CI trigger → UAT validation → production deploy trigger → result interpretation),
**Then** the full cycle completes within the ≤ 30-minute target without consulting any source outside the repository.
**And** the guide covers: how to identify the correct GHCR image tag, how to trigger the production deploy workflow via GitHub Actions UI, and how to interpret a successful deploy vs. an Auto-Revert result.

**Given** `docs/deployment/rollback.md` exists,
**When** a developer needs to manually revert production beyond what Auto-Revert covers,
**Then** the procedure covers: locating the previous image digest in GHCR, re-pinning it in the deployment configuration, and executing the SSH deploy command — without server-side improvisation.
**And** a developer with no prior context on the current incident can follow the procedure to restore production to the previous image.

---

## Epic 3: Backup, Recovery & Operational Readiness

A developer can monitor application health in production, recover from any failure scenario within RTO/RPO targets using scripted procedures, respond to any operational alert, and handle common failure incidents — all using self-contained documentation without improvising steps.

### Story 3.1: PostgreSQL Backup Automation

As a developer responsible for production data,
I want the Hetzner Volume snapshotted daily and `pg_dump` run every 6 hours with output pushed to Hetzner Object Storage,
So that data can be recovered to within 6 hours of any failure without manual intervention to trigger the backups.

**Acceptance Criteria:**

**Given** the daily snapshot automation is configured,
**When** 24 hours pass on the Node,
**Then** at least one Hetzner Volume snapshot is available — created automatically via the Hetzner API, not triggered manually.
**And** the snapshot automation is defined in version-controlled configuration (IaC or cron), not applied manually in the Hetzner UI.

**Given** the 6-hourly pg_dump automation is configured,
**When** 6 hours pass,
**Then** at least one `pg_dump` archive is available in Hetzner Object Storage — not stored on the Node's local disk.
**And** at least one dump is available at any point within the past 6 hours, satisfying the RPO < 6-hour target.

**Given** the backup automation has run,
**When** the Object Storage bucket is inspected,
**Then** dump files are present and their timestamps confirm the 6-hour cadence is being met.

---

### Story 3.2: Scripted Restore Process

As a developer responding to a data loss or Node failure,
I want an executable restore script in the repository that restores the application from either a volume snapshot or a `pg_dump`, with quarterly drill results recorded,
So that I can execute a full restore without improvising steps and within the RTO < 4-hour target.

**Acceptance Criteria:**

**Given** the restore script exists in the repository,
**When** a developer executes it against a volume snapshot,
**Then** the application is restored to a running, healthy state with data from the snapshot — without requiring any undocumented manual steps.
**And** the script is executable (not merely a prose runbook) and stored in version control.
**And** the script is idempotent — re-running it on a partially restored environment does not create an inconsistent state.

**Given** the restore script exists,
**When** a developer executes it against a `pg_dump` from Object Storage,
**Then** the application is restored with data from that dump, and data integrity is verified (e.g., row count check or health endpoint returns healthy post-restore).

**Given** a quarterly restore drill is due,
**When** a developer runs the restore script on a non-production environment,
**Then** the result (pass/fail, date, environment used) is recorded in a designated location in the repository.
**And** the drill confirms the RTO < 4-hour target is achievable via the scripted process.

---

### Story 3.3: External Uptime Monitoring & Alert Rules

As a developer responsible for production availability,
I want an external uptime monitor that alerts within 5 minutes of `/actuator/health` becoming unreachable, and version-controlled alert rules for disk, memory, and application health,
So that I am notified of production failures even when the entire Node — including the internal LGTM stack — is offline.

**Acceptance Criteria:**

**Given** the external uptime monitor is configured,
**When** `/actuator/health` becomes unreachable from outside the Node,
**Then** an alert fires within 5 minutes — even if the Node itself (including the LGTM stack) is completely offline.
**And** the monitor is independent of the Node: it does not run as a container on the Node.

**Given** the alert rules are defined in `alerts.yml`,
**When** a disk usage, memory, or application health threshold is breached,
**Then** an alert fires automatically and is routed to email and Slack — without any operator action required to trigger it.
**And** `alerts.yml` is committed to the repository — alert rules do not exist only as manual Grafana UI configuration.
**And** the `alerts.yml` configuration is version-controlled alongside the rest of the deployment setup.

---

### Story 3.4: Operational Documentation Suite

As a developer who needs to understand, operate, or recover any aspect of the production system,
I want a complete set of operational documents in `docs/deployment` covering backup/restore, Traefik/TLS, monitoring, and a failure runbook,
So that I can diagnose and resolve any operational incident without improvising steps or consulting sources outside the repository.

**Acceptance Criteria:**

**Given** `docs/deployment/backup-restore.md` exists,
**When** a developer initiates a restore from either a volume snapshot or a `pg_dump`,
**Then** the guide covers all steps: initiating the restore, verifying data integrity (row count check or health endpoint pass), and bringing the application back online.
**And** a developer can complete the full restore using the guide alone.

**Given** `docs/deployment/traefik-tls.md` exists,
**When** a developer needs to verify TLS certificate status or diagnose a failed renewal,
**Then** the guide explains how Traefik is configured, how certificates are issued and renewed, the expected renewal timeline, and the steps to take if a certificate is not renewed before expiry.

**Given** `docs/deployment/monitoring.md` exists,
**When** a developer needs to access Grafana or respond to an alert,
**Then** the guide explains how to access Grafana, what dashboards exist, what each configured alert means, and what action to take when each alert fires.
**And** every alert defined in `alerts.yml` has a corresponding documented response action in this guide.

**Given** `docs/deployment/runbook.md` exists with three failure scenarios (disk exhaustion, PostgreSQL service down, Redis OOM),
**When** a developer encounters one of these failures,
**Then** each scenario section includes: a detection method (how to confirm the failure), step-by-step remediation, and a verification check (how to confirm resolution).
**And** a developer can resolve each scenario by following the runbook alone, without improvising any step.
