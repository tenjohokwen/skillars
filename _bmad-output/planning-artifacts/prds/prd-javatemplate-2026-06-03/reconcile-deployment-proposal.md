# Input Reconciliation — deployment-proposal.md

## Gaps (present in input, missing/thin in PRD)

- **Cost estimate table** — The input provides a concrete monthly cost breakdown (CX32 €7, 100GB Volume €5, Snapshots €3, Object Storage €3, Total ~€18/month). The PRD contains no cost information whatsoever. This is operationally relevant: it sets budget expectations, informs operator decisions about snapshot retention (OQ-5), and helps justify the single-node choice.

- **Scaling path (concrete trigger + steps)** — The input spells out a specific three-step scaling path triggered at 150–200 concurrent users: (1) detach DB to a dedicated CX42 node, (2) scale app to a second CX32 + Hetzner Load Balancer, (3) isolate LGTM stack to a dedicated small node. The PRD's "Operational Requirements" section mentions the scaling trigger threshold but omits the CX42 DB node, the second CX32 for the app, and the Hetzner Load Balancer as concrete next-infrastructure steps. The mention in §6.2 (Out of Scope) only defers; it does not capture the roadmap.

- **Specific Loki/Prometheus retention values in requirements** — The input states exact retention targets: Loki 30 days, Prometheus 15 days. While the PRD's "Operational Requirements" section does include these values, FR-17 and FR-20 do not reference them as enforceable consequences, leaving the functional requirements weaker than the input intended.

- **Initial document intent / scope statement** — The input opens with an explicit statement of purpose: "all config for deployment," GitHub Actions configured, usage documentation in `docs/deployment`, Traefik configuration, DB replication/backup scripts, backup restoration scripts, and documentation of how to restore. This framing as an exhaustive config-plus-docs checklist — including explicit mention of Traefik configuration files and DB replication/backup scripts as deliverables — is not reflected as a completeness criterion in the PRD. The PRD describes documentation but does not enumerate configuration artifacts as required deliverables.

- **Deployment workflow SSH command specifics** — The input specifies the exact deploy command (`docker compose pull <service> && docker compose up -d --no-deps <service>`) as part of the workflow definition. The PRD correctly describes the behavior but does not capture this as a normative constraint on how the SSH-based deploy step must be implemented, leaving it open to deviation.

## Depth gaps (present but under-represented)

- **Graceful shutdown detail** — The input specifies `stop_grace_period: 30s` in `docker-compose.yml` as the mechanism for graceful shutdown. FR-10 captures the behavior (in-flight requests complete, ≤ 10-second downtime) but does not mention the 30-second grace period value. The PRD says "define a stop grace period" without the concrete value; an implementer has no lower bound to meet.

- **Docker log rotation specifics** — The input names exact values: `max-size: "10m"` and `max-file: "3"`. FR-20 captures the behavior (logs rotate, disk does not exhaust) but omits the specific limits. An implementer could satisfy FR-20 with wildly different values.

- **Rollback procedure** — The input describes rollback as: re-tag or re-pin the previous image digest in `docker-compose.yml`, then re-run the SSH deploy command. FR-23 (Rollback procedure) captures this exists as a document but does not require it to cover re-pinning the image digest specifically. The input's "re-pin the previous image digest" is the precise mechanism; the PRD abstracts it away.

- **Smoke test timing** — The input says the smoke test fires against `/actuator/health` "post-deploy." FR-6 states "within 60 seconds of service restart." The input is silent on a specific SLA for the smoke test check itself but is explicit about what triggers rollback (failed health check). The PRD adds the 60-second window as an improvement, but the specific health endpoint path (`/actuator/health`) appears in the input and only implicitly in the PRD glossary/UJ-1 narrative — it is not a testable consequence of FR-6.

- **External uptime monitoring specificity** — The input explicitly names Hetzner native platform monitoring as the tool and specifies it pings `/actuator/health`. FR-18 demotes the Hetzner tool to an assumption (tagged `[ASSUMPTION]`) and does not require the specific endpoint path as a testable consequence. The input treated this as a design decision, not an open question.

## Dropped qualitative intent

- **"Lean, cost-appropriate" framing** — The input opens with an explicit design philosophy: "lean, cost-appropriate deployment strategy optimized for initial scale of < 50 concurrent users." The PRD captures the < 50 user constraint and the single-node rationale in the Vision section, but the cost-consciousness framing — that every decision is made through the lens of minimizing spend at low scale — is not carried through the feature sections. This matters because it should govern implementer judgment calls (e.g., choosing cheaper alternatives, avoiding over-engineering).

- **"Noisy-neighbor" rationale for resource limits** — The input states the specific reason for requiring resource limits: "to prevent noisy-neighbor issues on the single node." FR-7 requires limits but omits this rationale. An implementer who understands the "why" is better equipped to set appropriate limit values; without it, limits could be set arbitrarily high and still technically satisfy the requirement.

- **Single-node as a deliberate, justified choice** — The input includes an explicit rationale for the 8GB node: "8GB is sufficient to comfortably host the JVM, DB, and monitoring stack if resource limits are enforced. Using a separate volume for PostgreSQL enables easier snapshotting and data management." The PRD Vision describes the intent but not the capacity reasoning. If this reasoning is not preserved, future developers may question or unnecessarily upgrade the node size.

- **Restore validation drill as a hard requirement** — The input states the restore process "MUST be scripted" and a "quarterly drill WILL be performed." This imperative tone is present in FR-16's consequences ("tested quarterly") but the "MUST be scripted in an automation repository" language elevates the script from documentation to an automation artifact. The PRD's FR-16 says "executable script or step-by-step runbook" — preserving optionality the input does not allow.

- **Secrets file mode (`600`) as a security requirement** — The input explicitly requires the `.env` file to have Unix permission mode `600`. FR-12 says the file is "stored outside the repository root (mode `600`)" in the description but does not include mode `600` as a testable consequence. This means the permission requirement could be overlooked during implementation without technically failing the acceptance criteria.

## Verdict

The PRD faithfully covers all major functional areas from the input and adds significant structure and testability that the input lacked; the most material gaps are the complete omission of the cost estimate (which informs budget and retention decisions), the loss of concrete scaling-path infrastructure details, and several specific implementation values (grace period, log rotation limits, secrets file permissions) that were stated precisely in the input but degraded to under-specified consequences in the PRD.
