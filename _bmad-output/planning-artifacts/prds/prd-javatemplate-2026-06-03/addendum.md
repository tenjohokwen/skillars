# Addendum — Deployment & Infrastructure PRD

*This document holds technical detail, mechanism choices, implementation values, and rationale that inform downstream architecture and implementation but do not belong in the PRD body.*

---

## Cost Estimate (Monthly)

Approximate infrastructure cost at initial scale (< 50 concurrent users):

| Component | Cost (Approx) |
| :--- | :--- |
| CX32 Node | €7.00 |
| 100GB Volume | €5.00 |
| Volume Snapshots | €3.00 |
| Object Storage | €3.00 |
| **Total** | **~€18.00/month** |

The single-node architecture was chosen specifically to keep this cost low while traffic remains below 50 concurrent users. The "noisy-neighbor" risk (one container exhausting shared resources) is mitigated by the resource limits required in FR-7 — not by multi-node isolation.

---

## Scaling Path (When to Revisit Single-Node)

When traffic approaches 150–200 concurrent users, the recommended progression is:

1. **Detach DB** — Migrate PostgreSQL to a dedicated CX42 node. The use of a separate Hetzner Volume (FR-14) makes this migration a volume detach + reattach, not a data migration.
2. **Scale App** — Provision a second CX32 for the application, and introduce a Hetzner Load Balancer to distribute traffic.
3. **Isolate Monitoring** — Migrate the LGTM Stack to a dedicated small node, freeing Node RAM for application workloads.

Because everything runs in Docker Compose, each step is a container relocation plus a DNS / load balancer update — no re-architecting required.

---

## Implementation Values

Concrete values referenced in the PRD that implementers must apply:

| Concern | Value | FR Reference |
| :--- | :--- | :--- |
| Container stop grace period | `stop_grace_period: 30s` in `docker-compose.yml` | FR-10 |
| Docker log rotation — max size | `max-size: "10m"` | FR-20 |
| Docker log rotation — max files | `max-file: "3"` | FR-20 |
| Server `.env` file permissions | Mode `600` (owner read/write only) | FR-12 |
| Loki log retention | 30 days | Operational Requirements |
| Prometheus metrics retention | 15 days | Operational Requirements |

---

## Auto-Revert Mechanism

FR-6 requires that a failed Smoke Test triggers Auto-Revert (restore the previous image and restart). The recommended implementation:

- The production deploy workflow receives the **target image tag** as an explicit input (FR-5).
- Before pulling the new image, the workflow records the **current running image digest** from `docker inspect` output and stores it as a workflow variable.
- If the Smoke Test fails, the workflow re-pins the recorded digest in the deploy step and restarts the service.
- The previous digest is not persisted between workflow runs — it is captured fresh on each deploy. This means Auto-Revert works for one level back only; deeper rollbacks require the manual rollback procedure (FR-23).

---

## Rejected Alternative: Two-Node Architecture

An earlier proposal (`server-proposal_old.md`) described a two-node setup: CX32 for the application + PostgreSQL primary, and a CX22 for the PostgreSQL replica + LGTM Stack (~€19/month). This was rejected in favour of the current single-node design for the following reasons:

- At < 50 concurrent users, replica traffic is near-zero; the second node adds cost without meaningful availability benefit.
- The LGTM stack coexists on the same node with enforced resource limits without degrading application performance at this scale.
- Operational simplicity is a first-order constraint (the ≤ 30-minute deploy target and the ≤ 2-hour first-time setup target both favour fewer nodes to manage).
