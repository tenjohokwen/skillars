---
title: "Deployment & Infrastructure PRD — javatemplate"
status: final
created: 2026-06-03
updated: 2026-06-03
---

# PRD: Deployment & Infrastructure — javatemplate

## 0. Document Purpose

This PRD defines the deployment and infrastructure capability for the javatemplate application. It is written for developers who will implement the CI/CD pipeline, infrastructure configuration, and operational documentation — and for any developer who subsequently uses those outputs to deploy the application. Features are grouped by capability area with globally numbered, stable FR IDs. Assumptions are tagged `[ASSUMPTION]` inline and indexed in §9. Technical mechanism choices (e.g., provisioning tooling, specific GitHub Actions syntax) live in `addendum.md`; this document captures *what* must be true, not *how* to achieve it.

---

## 1. Vision

The javatemplate application currently cannot be deployed. There is no documented path, no automated pipeline, and no operational runbook — meaning a developer who inherits the project must reconstruct the deployment setup from scratch, relying on tribal knowledge that does not exist.

This PRD defines a complete deployment capability that eliminates that problem. When implemented, a developer who arrives with SSH access to a server, a secrets file, a domain name, and working Docker knowledge should be able to provision a production environment for the first time in under two hours — and deploy subsequent releases in under thirty minutes — without asking anyone for help.

The deployment setup is intentionally lean and cost-appropriate — approximately €18/month for the initial production node, chosen to minimise operational overhead while traffic remains below 50 concurrent users. A semi-automated pipeline keeps the developer in control of when production changes go live. Security hardening, observability, and disaster recovery are built in from day one, not bolted on later. See `addendum.md` for the full cost breakdown, scaling path specifics, and the rationale for the single-node architecture.

---

## 2. Target User

### 2.1 Primary Persona

A developer (backend or full-stack, intermediate level) who has been handed the javatemplate repository. They understand Docker and can navigate a Linux server over SSH. They have the required credentials — server access, secrets file, domain — but have no prior context on how this specific project is structured or deployed.

### 2.2 Jobs To Be Done

- Stand up a production environment from scratch without needing to ask anyone.
- Ship a new application version to production with confidence it works before it goes live.
- Recover the application from a failure (server crash, data loss, bad deploy) using documented procedures.
- Understand what the application is doing in production at any time (logs, metrics, uptime).

### 2.3 Key User Journeys

**UJ-1. Developer provisions a server and deploys the application for the first time.**
- **Persona + context:** Developer with SSH access, secrets file, domain name, and Docker knowledge. No prior context on this setup.
- **Entry state:** Fresh Hetzner CX32 node running; DNS A record pointed at server IP; `docs/deployment/first-time-setup.md` open.
- **Path:** (1) Run provisioning script / follow runbook to configure the node. (2) Place the secrets file at the required path on the server. (3) Clone the repository and run the initial deploy command. (4) Verify the application health endpoint returns healthy. (5) Confirm TLS certificate has been issued and the domain is reachable over HTTPS.
- **Climax:** Application is live at the domain with a valid TLS certificate and a passing health check.
- **Resolution:** Developer has a running production environment. All monitoring and alerting are active. The path switches to UJ-2 for all future deployments.
- **Time target:** ≤ 2 hours end-to-end.
- **Edge case:** DNS has not yet propagated. Documentation must explain how to verify propagation and how long to wait before troubleshooting TLS issuance.

**UJ-2. Developer deploys a new version of the application.**
- **Persona + context:** Developer who has merged a change to `main` and wants to ship it to production.
- **Entry state:** Merge to `main` complete; CI has built the Docker image and pushed it to GHCR; developer has UAT server access.
- **Path:** (1) Pull the new image to the UAT server and validate manually. (2) When satisfied, navigate to GitHub Actions and trigger the production deploy workflow. (3) Monitor the post-deploy health check result in GitHub Actions output. (4) Receive a success or rollback notification.
- **Climax:** New version is live in production and the health check passes; or the pipeline auto-reverts to the previous image and the developer receives an alert.
- **Resolution:** Production is running the new version (success) or the previous stable version (auto-revert). Either state is safe and known.
- **Time target:** ≤ 30 minutes from UAT validation to confirmed production deploy.

---

## 3. Glossary

- **Node** — The single Hetzner CX32 virtual machine hosting all production workloads.
- **GHCR** — GitHub Container Registry; the image registry where built Docker images are stored.
- **UAT Server** — A separate remote server used by the developer for manual pre-production validation. Not part of the CI/CD pipeline.
- **Artifact** — The immutable Docker image built and tagged by the CI pipeline and pushed to GHCR.
- **Production Deploy** — The act of pulling a new Artifact to the Node and restarting the affected service(s).
- **Smoke Test** — An automated post-deploy health check against the application's health endpoint that determines whether the Production Deploy succeeded.
- **Auto-Revert** — The pipeline behaviour of restoring the previous Artifact and restarting services when the Smoke Test fails.
- **LGTM Stack** — The observability stack: Loki (logs), Grafana (dashboards), Tempo (traces), Prometheus (metrics).
- **Volume** — A Hetzner-managed block storage volume attached to the Node, used for PostgreSQL data.
- **Object Storage** — Hetzner-managed object storage used for PostgreSQL logical backup dumps.
- **RTO** — Recovery Time Objective; the maximum acceptable time to restore the application after a failure.
- **RPO** — Recovery Point Objective; the maximum acceptable data loss window.
- **IaC** — Infrastructure as Code; configuration managed in version-controlled files rather than applied manually.

---

## 4. Features

### 4.1 Server Provisioning

**Description:** A one-time setup path that takes a fresh Hetzner CX32 node to a fully configured, application-ready server. Provisioning is performed once per Node lifecycle — it is not part of the regular deploy cycle. The process must be scripted or documented as a minimal-step runbook so it is fast to execute, easy to follow, and easy to maintain as the stack evolves. Realizes UJ-1.

**Functional Requirements:**

#### FR-1: Minimal-step node provisioning

A developer can provision a fresh Node to a production-ready state by following a single documented procedure (script or runbook). The procedure installs all required system dependencies, configures SSH hardening, installs Docker and Docker Compose, and prepares the directory structure for secrets and application data.

**Consequences (testable):**
- A developer following the procedure on a fresh CX32 reaches a state where `docker compose up` can be run without additional manual steps.
- The procedure completes without requiring the developer to look outside `docs/deployment`.
- The full provisioning path completes within the ≤ 2-hour first-time setup target (UJ-1).

#### FR-2: Firewall provisioned as IaC

Hetzner Cloud Firewall rules for the Node are defined and applied via IaC to prevent configuration drift.

**Consequences (testable):**
- Inbound TCP 80 and 443 are open from all sources.
- Inbound TCP 22 is restricted to allowlisted IP(s) only; all other inbound traffic is blocked. [ASSUMPTION: The allowlisted SSH IP(s) are known and provided by the operator before provisioning.]
- Firewall state can be reproduced by running the IaC tooling against the Hetzner API.

#### FR-3: DNS setup documented

The first-time setup documentation covers domain name configuration: how to purchase a domain, how to create a DNS A record pointing to the Node's public IP, how to verify DNS propagation, and how long to wait before proceeding to TLS issuance.

**Consequences (testable):**
- A developer with no prior context can complete DNS configuration by following the documentation alone.
- Documentation accounts for propagation delay and advises on troubleshooting TLS issuance if the certificate is not issued promptly.

---

### 4.2 CI/CD Pipeline

**Description:** A semi-automated pipeline that builds Docker images on merge to `main` and gives the developer a manual trigger to deploy a validated Artifact to production. The developer retains control of when production changes. UAT validation is manual and occurs outside the pipeline on a separate UAT Server. Realizes UJ-2.

**Functional Requirements:**

#### FR-4: Automated image build and publish

On every merge to `main`, the pipeline automatically builds an immutable Docker image tagged with the commit SHA and pushes it to GHCR.

**Consequences (testable):**
- Every merged commit produces a corresponding tagged image in GHCR within the pipeline run.
- The image tag is deterministic and traceable to its source commit.
- Build failures are reported and block the pipeline.

#### FR-5: Manual production deploy trigger

A developer can trigger a Production Deploy via the GitHub Actions UI by selecting the target image tag. No CLI access or direct SSH is required to initiate the deploy.

**Consequences (testable):**
- The deploy workflow is triggerable from the GitHub Actions UI.
- The workflow accepts the target image tag as an input. [ASSUMPTION: The developer identifies the correct image tag from GHCR before triggering.]
- The workflow connects to the Node via SSH, pulls the specified image, and restarts the affected service with no additional manual steps on the server.

#### FR-6: Post-deploy Smoke Test and Auto-Revert

Immediately after a Production Deploy, the pipeline runs a Smoke Test against the application health endpoint. A failed Smoke Test triggers Auto-Revert and alerts the deployer.

**Consequences (testable):**
- The Smoke Test queries the health endpoint within 60 seconds of service restart.
- A failed Smoke Test triggers Auto-Revert: the previous image digest is re-pinned and services are restarted.
- The deployer receives an alert on both successful deploy and Auto-Revert.
- A failed Smoke Test marks the build as failed in GitHub Actions.

**Notes:** Notification channels are email and Slack webhook. Both must be configured in GitHub Actions secrets before the first production deploy. (OQ-1 resolved.)

---

### 4.3 Infrastructure & Container Configuration

**Description:** All production workloads run in Docker Compose on the single Node. Every service is defined with resource constraints, health checks, and restart policies. Traefik acts as the sole ingress point and manages TLS automatically. Network isolation prevents direct external access to any service other than through Traefik. Realizes UJ-1, UJ-2.

**Functional Requirements:**

#### FR-7: All services defined in Docker Compose with resource limits

Every service (Application, PostgreSQL, Redis, Traefik, LGTM Stack) is defined in `docker-compose.yml` with explicit CPU and memory limits, a health check, and `restart: unless-stopped`.

**Consequences (testable):**
- No service can consume unbounded CPU or memory on the Node.
- Traefik routes traffic only to containers whose health check reports healthy.
- Any service that crashes restarts automatically without operator intervention.

#### FR-8: Traefik handles TLS termination automatically

Traefik obtains and renews TLS certificates via Let's Encrypt. The developer provides only the domain name; certificate issuance and renewal require no further manual action.

**Consequences (testable):**
- The application is reachable over HTTPS at the configured domain with a valid certificate after first-time setup.
- Certificate renewal occurs automatically before expiry.

#### FR-9: Docker network isolation

All services communicate over a named internal bridge network. Only Traefik is bound to host ports 80 and 443. No other service exposes ports to the host network.

**Consequences (testable):**
- PostgreSQL, Redis, and LGTM Stack ports are not reachable from outside the Node.
- All external traffic enters exclusively through Traefik.

#### FR-10: Graceful shutdown on deploy

Services define a stop grace period that allows in-flight requests to complete before container termination during a Production Deploy.

**Consequences (testable):**
- A Production Deploy does not abruptly terminate active connections.
- Expected deploy downtime is ≤ 10 seconds under normal load.

---

### 4.4 Security Hardening

**Description:** The Node is hardened at the network, SSH, and application layer. Secrets are never committed to version control. The Traefik management interface is not publicly accessible. Realizes UJ-1.

**Functional Requirements:**

#### FR-11: SSH key-only authentication

The Node accepts SSH connections via key-based authentication only. Password-based login is disabled. `fail2ban` is installed and configured to block repeated failed authentication attempts.

**Consequences (testable):**
- SSH password authentication is rejected by the server.
- `fail2ban` is active and configured with a block policy for repeated failures.

#### FR-12: Secrets never committed to version control

All secrets are injected at runtime. GitHub Actions secrets are used for CI/CD. A `.env` file stored outside the repository root (mode `600`) is used on the Node. No secrets appear in any committed file.

**Consequences (testable):**
- A full scan of the repository history contains no secrets.
- The `.env` file is listed in `.gitignore`.
- Documentation specifies every required secret, its format, and placement — without embedding values.

#### FR-13: Traefik dashboard not publicly accessible

The Traefik management dashboard is disabled in production or protected with BasicAuth middleware. It is not reachable without authentication from outside the Node. [ASSUMPTION: Preference between disabling and BasicAuth protection is confirmed by the operator. See OQ-6.]

**Consequences (testable):**
- An unauthenticated request to the Traefik dashboard endpoint returns 401 or a connection refusal.

---

### 4.5 Backup & Disaster Recovery

**Description:** Application data is protected by two complementary mechanisms: daily block-level Volume snapshots and frequent PostgreSQL logical dumps to Object Storage. Restore procedures are fully scripted and validated by quarterly drills. RTO < 4 hours, RPO < 24 hours. Realizes UJ-1 (via documentation).

**Functional Requirements:**

#### FR-14: Daily volume snapshots

The Hetzner Volume is snapshotted daily via the Hetzner API. Snapshots are retained for a period sufficient to meet the RPO target. [ASSUMPTION: Snapshot retention period is confirmed by the operator. See OQ-5.]

**Consequences (testable):**
- At least one volume snapshot is available at any point within the past 24 hours.
- Snapshot creation is automated, not manually triggered.

#### FR-15: 6-hourly PostgreSQL logical backups

`pg_dump` runs every 6 hours and pushes the dump to Object Storage (not to the Node's local disk).

**Consequences (testable):**
- At least one dump is available at any point within the past 6 hours.
- Dumps are stored in Object Storage.

#### FR-16: Scripted restore process

The full restore process — from snapshot or `pg_dump` to a running application — is implemented as an executable script stored in the repository and documented in `docs/deployment`.

**Consequences (testable):**
- A developer can execute a full restore by running the restore script, without improvising steps.
- The restore script is stored in the repository and version-controlled alongside the rest of the deployment configuration.
- The restore process is tested quarterly via a drill on a non-production environment; results are recorded.

---

### 4.6 Monitoring & Observability

**Description:** The LGTM Stack provides internal observability. External uptime monitoring provides independent alerting if the Node goes offline. Alert thresholds are defined in version-controlled configuration. Docker log rotation prevents local disk saturation. Realizes UJ-1 (via documentation).

**Functional Requirements:**

#### FR-17: LGTM stack deployed and configured

Prometheus, Grafana, Loki, and Tempo are deployed on the Node as part of the standard Compose stack with resource limits, health checks, and restart policies consistent with FR-7. Grafana is accessible to authorised operators.

**Consequences (testable):**
- Application logs are queryable in Loki.
- Application metrics are visible in Grafana dashboards.

#### FR-18: External uptime monitoring

An external uptime monitor independent of the Node pings the application health endpoint at a regular interval and alerts when the endpoint is unreachable. [ASSUMPTION: Hetzner native platform monitoring is sufficient for external uptime alerting. Confirm if a third-party service is preferred.]

**Consequences (testable):**
- An alert fires within 5 minutes of the health endpoint becoming unreachable from outside the Node.
- The alert fires even if the entire Node (including the LGTM stack) is offline.

#### FR-19: Alert thresholds in version-controlled configuration

Alert rules for disk usage, memory, and application health are defined in `alerts.yml` (version-controlled) and routed to email and Slack.

**Consequences (testable):**
- Alert rules are not configured manually in the Grafana UI only; they exist in committed configuration.
- Alerts fire at configured thresholds without operator intervention.

#### FR-20: Docker log rotation configured

The Docker logging driver is configured with a maximum file size and file count on the Node to prevent log files from exhausting local disk.

**Consequences (testable):**
- Log files for any single container do not exceed the configured maximum size.
- Old log files are rotated automatically.

---

### 4.7 Deployment Documentation

**Description:** `docs/deployment` is the single source of truth for all deployment and operations knowledge. Every procedure a developer needs — from purchasing a domain to recovering from a disk-full event — is covered. No step in any document requires the developer to consult an external source or ask another person. Realizes UJ-1, UJ-2.

**Functional Requirements:**

#### FR-21: First-time setup guide

A guide covering the complete first-time setup path: domain purchase and DNS configuration, server provisioning, secrets placement, initial deploy, and verification.

**Consequences (testable):**
- A developer following the guide on a fresh Node reaches a live, healthy, TLS-enabled environment within the ≤ 2-hour target without external assistance.

#### FR-22: Ongoing deployment guide

A guide covering the regular deploy cycle: triggering the CI build, pulling to the UAT Server for manual validation, triggering the production deploy workflow, and interpreting the result.

**Consequences (testable):**
- A developer following the guide completes a production deploy within the ≤ 30-minute target.

#### FR-23: Rollback procedure

Documentation of the manual rollback procedure: how to identify the previous image digest, re-pin it, and restart services — for cases requiring manual intervention beyond Auto-Revert.

**Consequences (testable):**
- A developer can manually revert production to the previous image using the documented procedure without server-side improvisation.
- The procedure covers: locating the previous image digest in GHCR, re-pinning it, and restarting the affected service.

#### FR-24: Backup and restore guide

Documentation covering how to initiate a restore from a volume snapshot or `pg_dump`, how to verify data integrity after restore, and how to bring the application back online.

**Consequences (testable):**
- A developer can initiate a full restore from either backup source by following the guide alone.
- Data integrity verification steps are included and produce a verifiable outcome (e.g., row count check, application health endpoint passes post-restore).

#### FR-25: Secrets reference

A list of every required secret, its format, where it must be placed (GitHub Actions secrets vs. server `.env`), and how to generate or obtain it. No secret values are included.

**Consequences (testable):**
- Every secret required by the application and the CI/CD pipeline appears in the reference.
- A developer who has never seen the project before can locate and place every secret using the reference alone.
- No secret value appears anywhere in the document.

#### FR-26: Traefik and TLS reference

Explanation of how Traefik is configured, how TLS certificates are issued and renewed, and what to do if a certificate fails to renew.

**Consequences (testable):**
- A developer can verify TLS certificate status and diagnose a failed renewal by following the guide alone.
- The guide states the expected renewal timeline and the steps to take if a certificate is not renewed before expiry.

#### FR-27: Monitoring reference

Explanation of how to access Grafana, what dashboards exist, what each alert means, and what action to take when an alert fires.

**Consequences (testable):**
- A developer can access Grafana and navigate to the relevant dashboard by following the guide alone.
- Every configured alert has a corresponding documented response action in the guide.

#### FR-28: Operational runbook

A runbook covering three common failure scenarios: disk exhaustion on the Node, PostgreSQL service down, and Redis OOM condition.

**Consequences (testable):**
- Each scenario includes: a detection method (how to confirm the failure), step-by-step remediation, and a verification check (how to confirm resolution).
- A developer can resolve each scenario by following the runbook alone, without improvising steps.

---

## 5. Non-Goals

- **Blue-green or rolling deployments** — in-place update with ≤ 10-second downtime is acceptable at current scale.
- **Automated UAT/staging pipeline** — UAT validation is manual; CI does not deploy to a staging environment automatically.
- **Kubernetes or container orchestration** — Docker Compose on a single node is the target.
- **CDN, WAF, or DDoS protection** — out of scope at this traffic volume.
- **Multi-region or high-availability setup** — single-node architecture is intentional for < 50 concurrent users.
- **Automated domain purchase** — developer purchases a domain manually; documentation covers DNS configuration from that point.
- **Database replication** — deferred to the scaling path (> 150 concurrent users).

---

## 6. MVP Scope

### 6.1 In Scope

- One-time server provisioning procedure (script or runbook) with IaC-managed firewall
- `docker-compose.yml` with all services, resource limits, health checks, and restart policies
- Traefik with automatic TLS via Let's Encrypt and network isolation
- GitHub Actions: automated build on merge to `main`, push to GHCR, manual production deploy trigger, Smoke Test, Auto-Revert, deploy notification
- SSH hardening + `fail2ban`
- Secrets management (GitHub Actions secrets + server `.env`)
- Daily volume snapshots + 6-hourly `pg_dump` to Object Storage with scripted restore
- LGTM stack + external uptime monitoring + version-controlled alert rules + Docker log rotation
- Full `docs/deployment` covering all eight areas (FR-21 through FR-28)

### 6.2 Out of Scope for MVP

- Automated UAT deployment — deferred; UAT is manual [NOTE FOR PM: revisit if team size grows]
- Database replication — deferred to scaling path
- Horizontal scaling / load balancer — deferred to scaling path (> 150 concurrent users)
- LGTM stack on a dedicated node — deferred to scaling path
- Blue-green or canary deployments — deferred; downtime budget acceptable at current scale

> **See `addendum.md`** for cost breakdown (~€18/month), concrete scaling path steps, implementation values (grace periods, log rotation limits, file permissions), and Auto-Revert mechanism detail.

---

## Cross-Cutting NFRs

- **Configuration as code.** All infrastructure state is version-controlled. No production configuration exists only in a UI or applied manually after initial provisioning.
- **Self-contained documentation.** Every procedure in `docs/deployment` is complete without external references. A developer following any guide should never need to open a tab outside the repository.
- **Idempotency.** Scripts must be safe to re-run without causing unintended side effects where technically feasible.
- **Resource bounds on all containers.** No container may run without CPU and memory limits defined.
- **Deploy downtime budget.** In-place container restart produces ≤ 10 seconds of downtime. Acceptable at < 50 concurrent users.
- **Secrets hygiene.** No secret ever appears in a committed file, a log output, or a CI output visible in GitHub Actions.

---

## Operational Requirements

- **RTO:** < 4 hours from failure detection to restored service.
- **RPO:** < 24 hours (volume snapshot); < 6 hours (PostgreSQL logical backup).
- **Restore validation:** Quarterly drill on a non-production environment; result recorded.
- **Log retention:** Loki 30 days; Prometheus metrics 15 days. Limits enforced in configuration.
- **Uptime alerting:** External monitor alerts within 5 minutes of health endpoint failure.
- **Scaling trigger:** When traffic approaches 150–200 concurrent users, re-evaluate single-node architecture (detach DB, add second app node, introduce load balancer, isolate LGTM stack).

---

## 7. Success Metrics

**Primary**
- **SM-1:** A developer with the required prerequisites completes UJ-1 (first-time setup) in ≤ 2 hours with no external assistance. Validates FR-1, FR-3, FR-21.
- **SM-2:** A developer completes UJ-2 (ongoing deploy) in ≤ 30 minutes from UAT validation to confirmed production. Validates FR-5, FR-6, FR-22.
- **SM-3:** No step in any `docs/deployment` guide causes a developer to seek help outside the documentation. Validates FR-21 through FR-28.

**Secondary**
- **SM-4:** A failed Smoke Test triggers Auto-Revert and a deployer alert within 2 minutes. Validates FR-6.
- **SM-5:** The scripted restore process completes with verified data integrity in the quarterly drill. Validates FR-16.
- **SM-6:** A disk, memory, or health threshold breach produces an alert without operator action. Validates FR-18, FR-19.

**Counter-metrics (do not optimize)**
- **SM-C1:** Documentation length is not a proxy for completeness. A developer asking a question that `docs/deployment` should have answered is a failure regardless of page count.
- **SM-C2:** Deploy speed is not optimized at the cost of Smoke Test coverage. Weakening the health check to speed up deploys defeats Auto-Revert.

---

## 8. Open Questions

1. **OQ-1:** ~~What notification channel should failed deploys and alerts route to?~~ **RESOLVED:** Email and Slack webhook.
2. **OQ-2:** What is the UAT server setup? Is it a separate Hetzner node or another environment? Documentation must cover UAT server setup if it does not already exist.
3. **OQ-3:** Which IP(s) should be allowlisted for SSH access in the Hetzner firewall? Required before provisioning.
4. **OQ-4:** Has a domain name been purchased? If not, documentation must cover domain selection and purchase as step zero of the first-time setup guide.
5. **OQ-5:** What is the desired retention period for Hetzner volume snapshots? (Cost scales with retention; proposal is silent on this.)
6. **OQ-6:** Should the Traefik dashboard be disabled entirely or protected with BasicAuth? Either satisfies FR-13 — confirm operator preference.

---

## 9. Assumptions Index

- **§4.1 / FR-2** — SSH allowlist IPs are known and provided by the operator before provisioning is run. (See OQ-3.)
- **§4.2 / FR-5** — The developer identifies the correct GHCR image tag from GHCR manually before triggering the production deploy workflow.
- **§4.4 / FR-13** — Preference between disabling the Traefik dashboard and protecting it with BasicAuth is confirmed by the operator. (See OQ-6.)
- **§4.5 / FR-14** — Snapshot retention period is confirmed by the operator; cost scales with retention. (See OQ-5.)
- **§4.6 / FR-18** — Hetzner native platform monitoring is sufficient for external uptime alerting; a third-party service is not required. If a third-party service is preferred, this must be confirmed before implementation.
- **§2.1** — The deploying developer has working Docker knowledge but no prior context on this specific project setup.
