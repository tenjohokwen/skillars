# Restore Drill Log

Append one row per quarterly drill. Record every drill, including failures.
Drills should target a non-production environment to avoid production data exposure.

| Date | Environment | Method | Result | RTO Achieved | Notes |
|---|---|---|---|---|---|
| 2026-06-01 | local docker (non-prod) | pg_dump | PASS | 38 min | Example row — delete before first real drill |
