
* This document is an old proposal and is just here for reference

Year 1 Setup (2 Nodes, No LB)

Node 1 — CX32 (4 vCPU / 8GB RAM / ~€7/mo)

Runs: Spring Boot + Redis + PostgreSQL Primary

8GB is comfortable: ~1GB JVM heap, ~50MB Redis, ~2GB PostgreSQL shared_buffers, 4GB+ headroom.

Attach a Hetzner Volume (100GB, ~€5/mo) for the PostgreSQL data directory — not the local SSD. This lets you snapshot, resize, and detach independently.

Node 2 — CX22 (2 vCPU / 4GB RAM / ~€4/mo)

Runs: PostgreSQL Replica + Full LGTM stack

The replica is mostly idle at 50 users, so it coexists fine with the LGTM stack. Grafana + Prometheus + Loki + Tempo together use ~2–2.5GB RAM. 4GB fits with room to breathe.

No load balancer needed — direct traffic to Node 1's public IP. Add the LB when you introduce a second app server.

  ---
Cost

┌──────────────────────────┬─────────┐
│        Component         │  Cost   │
├──────────────────────────┼─────────┤
│ Node 1 (CX32)            │ €7      │
├──────────────────────────┼─────────┤
│ Node 2 (CX22)            │ €4      │
├──────────────────────────┼─────────┤
│ Volume 100GB             │ €5      │
├──────────────────────────┼─────────┤
│ Object Storage (backups) │ €3      │
├──────────────────────────┼─────────┤
│ Total                    │ ~€19/mo │
└──────────────────────────┴─────────┘
  
---
Migration to the 5-Node Setup

When you approach ~150–200 concurrent users:
1. Spin up dedicated app servers (CX32 × 2) + LB11
2. Move PostgreSQL to its own CX42
3. Move LGTM to its own CX42
4. Promote the current replica or provision a fresh one

Since everything runs in Docker Compose, migration is container move + DNS/LB update — no re-architecting.

