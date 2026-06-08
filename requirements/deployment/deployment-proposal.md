# Deployment & Infrastructure Proposal (v2)
The aim of this document is to lay down the deployment spec.
The end goal is to have all config for deployment
This application should have all it needs to be deployed
Some of the expections are:
  * github actions are configured for this repo and usage documentation put in docs/deployment
  * Traefik configuration, db replication/backup scripts, backup restoration scripts and documentation of how to restore and other precautions around the restoration written in docs/deployment
This proposal outlines a lean, cost-appropriate deployment strategy optimized for an initial scale of < 50 concurrent users.

## 1. Server Architecture
We will use a single-node setup to minimize operational complexity and infrastructure cost.

### Primary Node (Hetzner CX32)
*   **Specs:** 4 vCPU / 8GB RAM
*   **Storage:** Local SSD + 100GB Hetzner Volume (for PostgreSQL data)
*   **Workloads:**
    *   Spring Boot (App)
    *   PostgreSQL (DB)
    *   Redis (Cache/Session)
    *   Traefik (Reverse Proxy + TLS)
    *   LGTM Stack (Prometheus, Grafana, Loki, Tempo)

**Rationale:** 8GB is sufficient to comfortably host the JVM, DB, and monitoring stack if resource limits are enforced. Using a separate volume for PostgreSQL enables easier snapshotting and data management.

---

## 2. Deployment Strategy: In-Place Update

We will use simple container restarts via Docker Compose. We avoid more complex patterns (like Blue-Green or multi-node Rolling Updates) to maintain simplicity while traffic remains low.

*   **Workflow:**
    1.  A merge to `main` (or a Git tag) triggers a GitHub Actions pipeline.
    2.  GitHub Actions builds and pushes an immutable image to GHCR.
    3.  The server is updated via SSH: `docker compose pull <service> && docker compose up -d --no-deps <service>`.
*   **Downtime:** This is a stop-then-start update. Expect ~5–10 seconds of downtime per deploy. This is acceptable at current scale.
*   **Graceful Shutdown:** To minimize request drops, services will define `stop_grace_period: 30s` in `docker-compose.yml` to allow active requests to finish before container termination.
*   **Smoke Testing:** The CI pipeline will trigger a post-deploy health check (a "smoke test") against the `/actuator/health` endpoint. If the check fails, the deployment pipeline will automatically revert to the previous image digest, restart the services, and send an alert notification to the deployer, marking the build as failed.
*   **Rollback:** Re-tag or re-pin the previous image digest in `docker-compose.yml` and re-run step 3. 

---

## 3. Operational Requirements & Safeguards

### Security Hardening (Critical)

**Hetzner Cloud Firewall** must be configured via Terraform to avoid configuration drift:
*   Allow inbound TCP 80/443 from all sources.
*   Allow inbound TCP 22 from specific IP(s) only. Block all others.

**SSH hardening** on the node:
*   Disable password-based SSH login.
*   Use key-only authentication.
*   Install and configure `fail2ban`.

**Traefik dashboard:**
*   Disable in production, or protect with BasicAuth middleware.

### Secrets Management
Injected at runtime (never committed).
*   GitHub Actions secrets for CI/CD.
*   Server: `.env` file outside repository root (mode `600`).

### Resource Constraints (Essential)
Every container MUST define `deploy.resources.limits` to prevent noisy-neighbor issues on the single node.

### Container Health Checks & Restart Policy
Every service must define a `healthcheck` and `restart: unless-stopped` policy. Traefik will only route to healthy containers.

### Docker Network Isolation
Use a named bridge network (`internal`). Traefik is the only service bound to host ports 80/443.

### Automated TLS/SSL
Traefik handles TLS termination via Let's Encrypt (HTTP-01).

### Backup & Disaster Recovery (DR)
*   **Objectives:** RTO < 4 hours, RPO < 24 hours.
*   **Backup Strategy:**
    *   Daily block-level volume snapshots via Hetzner API.
    *   6-hourly PostgreSQL logical `pg_dump` pushed to Hetzner Object Storage.
*   **Restore Validation:** The restore process MUST be scripted in an automation repository. A quarterly drill will be performed to verify data integrity using these scripts.

### Monitoring & Log Retention
*   **Loki/Prometheus:** Set strict retention (e.g., 30d/15d) to prevent disk exhaustion.
*   **External Uptime Monitoring:** Configure Hetzner's native platform monitoring to ping the application's health endpoint (`/actuator/health`) from outside the node. This ensures independent alerting if the entire node (including the local LGTM stack) goes offline.
*   **Log Rotation:** Configure the Docker `json-file` logging driver with `max-size: "10m"` and `max-file: "3"` as a safety net against system-level log saturation.
*   **Alerting:** Alert thresholds (disk usage, memory, app health) will be configured in `alerts.yml`.

---

## 4. Cost Estimate (Monthly)

| Component | Cost (Approx) |
| :--- | :--- |
| CX32 Node | €7.00 |
| 100GB Volume | €5.00 |
| Volume Snapshots | €3.00 |
| Object Storage | €3.00 |
| **Total** | **€18.00** |

---

## 5. Scaling Path
When traffic approaches 150–200 concurrent users:
1.  **Detach DB:** Migrate PostgreSQL to a dedicated CX42 node.
2.  **Scale App:** Provision a second CX32, move the App to a multi-node cluster, and introduce a Hetzner Load Balancer.
3.  **Isolate Monitoring:** Migrate the LGTM stack to a dedicated small node.
