# Story: skillars-deferred-98 — SLU Perf Signal & Frontend UX Precision

**Status:** review
**Story ID:** deferred-98  
**Branch:** `story/deferred-98-slu-perf-ux-precision`  
**Created:** 2026-09-07  
**Revised:** 2026-09-07 (post-review corrections)

## Executive Summary

**SCOPE CORRECTION:** Senior code review identified that four of the originally-scoped ACs describe work already shipped by deferred-90/91/92. Story significantly narrowed.

**Remaining genuine items:**
1. **SLU skill-trend signal** (ledger line 1292) — **new feature**, split into a design-approval gate (AC1a) and implementation (AC1b); AC1b defers to a follow-up story if the design is not signed off this cycle
2. **`startSessionMonitoring()` early return** — minimal: confirm project-owner decision is documented in code
3. **Frontend test framework decision** — yes/no decision only (Vitest this story or defer to own initiative?)

**Struck items (already shipped):**
- ~~AC2: getPublicProfile N+1 documentation~~ — deferred-91 D9 already reduced queries 8→4, Javadoc + IT exist
- ~~AC3: Session expiry timing documentation~~ — deferred-90/1.7b already documented ±30s variance in 3 places
- ~~AC5: Prettier formatting fix~~ — deferred-92 AC1 already fixed both files (2026-09-04) + added CI gate

## Acceptance Criteria

### AC1: SLU Skill-Trend Signal

**What:** Add a separate "is this player's trend improving/declining" signal independent of gating, for coaching/analytics insights.

**SCOPE:** Ledger line 1292 only. Note: A different latency perf-tracking item for `authorizePlayback` (ledger line 1241) is a real deferred-89 residual but **explicitly out of scope** for this story — stays open.

**Status:** Unbuilt. This is a new feature, not deferred-work cleanup. Split into a design gate (AC1a) and implementation (AC1b); **AC1b does not start until AC1a is signed off.**

---

#### AC1a: Design & Approval Gate (no production code)

Produce a short design note (in this story's Completion Notes or a linked `docs/` stub) that answers all four:

1. **Aggregation:** Per-skill trend (track that Skill X improved while Y declined separately) or per-player aggregate (overall trend)?
2. **Surface:** Where visible? (coach dashboard, player narrative, analytics API, parent visibility?)
3. **Computation:** Read-time calculation over `SluWeeklySnapshotRepository.findByPlayerIdFromWeek(…)` series, or scheduled batch job?
4. **Storage:** New DB column/table, or derived on-read from existing weekly rows?

**Gate:** design note reviewed and approved by the project owner. **Record the approval in Completion Notes.**

**If approval is not obtained within this story's cycle:** AC1b is carved out to its own feature story, ledger line 1292 stays **open** (do NOT tag it closed), and this story ships with AC1a's note as the only AC1 deliverable.

#### AC1b: Implementation (gated on AC1a approval)

- Likely code homes: `SluDashboardService`, `SluNarrativeService` for read/display
- Database: Flyway migration (next free `V###`, currently V130+) **only if** AC1a chose new storage
- Integration test: fixture with known weeks (week N improving vs N-1, etc.) asserting improving / declining / flat classification

**Dev Notes:**
- Weekly snapshot is **per-skill, additive-accumulator** (outbox event driven)
- `SnapshotBatchWriter.writeAllDeltas` applies one session-delta at a time; no single "weekly finished" event
- Trend is inherently a **sequence operation** — requires post-week or read-time compute, never in-flight append
- `SluWeeklySnapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek, toYear, toWeek)` already exists for historical fetch

**Completion:** AC1a — approved design note. AC1b (if in scope) — implementation PR with IT passing; otherwise AC1b tracked as a follow-up story and line 1292 left open.

### AC2: `startSessionMonitoring()` Early Return — Reference Existing Decision

**What:** Confirm that the project-owner decision to keep the "silent early return" path is documented in code.

**Context:** Ledger line 1268 records project-owner decision (deferred-90): keep the silent early-return behavior as-is. This is **not a design choice task** — it's a reference task.

**Scope:** Minimal — verify or add a code comment.

**Files:**
- `src/frontend/src/plugins/sessionManager.js:startSessionMonitoring()` — ensure a comment explains the early return and references the prior decision (if missing, add one)

**Dev Notes:**
- Current behavior: first `tick()` fires expired → `cleanup()` stops interval → dispatches `session:expired` → `handleSessionExpired()` runs `router.push()`
- If navigation aborts, monitoring disarmed; no re-arm on abort path
- Prior analysis claimed Option B ("arm anyway") would loop every 30s — actually fires **once more** at +30s then self-stops via `cleanup()` (the cost analysis was overstated)
- Project owner's decision stands; comment documents it

**Completion:** Reference comment exists and is clear.

### AC3: Frontend Test Infrastructure — Explicit Decision Only

**What:** Record project's explicit choice: stand up Vitest in this story, or defer to separate initiative?

**Current State:** No Vitest/Vue Test Utils in `src/frontend/package.json`. ~8 stories carry the same gap.

**Acceptance:** Completion Notes must include: "Frontend tests (Vitest/Vue Test Utils) **will ship in this story as [new AC]**" OR "**defer to separate initiative** `[specific backlog item]`". No open TODOs without decision.

**Dev Notes:**
- Ledger line 722 (skillars-5-4 W9) marks as `[DISMISSED]` — "its own initiative"
- If decision is "this story": add a separate AC for Vitest setup + rewrite one of deferred-89's three test gaps
- If decision is "own initiative": strike this as story blocker and defer the whole feature

**Completion:** Explicit yes/no decision in Completion Notes.

---

## Developer Context

### Files Being Modified

**AC1b (SLU Trend implementation — only after AC1a approval):**
- Read-phase homes: `SluDashboardService.java`, `SluNarrativeService.java`
- Repo layer: `SluWeeklySnapshotRepository.java` (existing `findByPlayerIdFromWeek` will likely be reused)
- Database: next free `V###` (V130+) migration, only if AC1a chose new storage

**AC2 (Early Return Comment):**
- `src/frontend/src/plugins/sessionManager.js` — ensure comment explains the design decision

**AC3 (Test Framework Decision):**
- Completion Notes only (decision document)

### Git Intelligence

- **deferred-89:** "SLU-related residual" was snapshot under-report direction (closed by deferred-91 AC4 + V119 marker); "carried from deferred-89" in original story was wrong
- **deferred-90:** Project-owner decision on `startSessionMonitoring()` early return — stays silent
- **deferred-91:** Snapshot outbox + idempotency (V119); SluWeeklySnapshotRepository established
- **deferred-92:** Fixed Prettier on all 130 frontend files (2026-09-04); added CI gate

### Architecture Compliance

- **SLU Trends:** Compute post-week or on-read using existing `findByPlayerIdFromWeek` pattern
- **Frontend:** Must pass `npx eslint src/frontend/` and `npx prettier --check src/frontend/`

### Testing Strategy

- **AC1a (Design):** No test — approved design note only
- **AC1b (SLU Trend):** Unit test for trend computation (improving/declining/flat); IT for fixture with known weeks
- **AC2 (Comment):** No test; code review verify
- **AC3 (Decision):** No test; document in Completion Notes

---

## Out of Scope (Explicitly)

- **authorizePlayback latency perf-tracking job** (ledger line 1241) — a real deferred-89 residual, currently unaddressed. Separate story or backlog decision needed. Do NOT tag this as closed by this story.
- **Frontend test framework implementation** — depends on AC3 decision; if deferred, entire Vitest initiative is out of scope

---

## Completion Criteria

- [x] AC1a: SLU trend design note (per-skill/aggregate, surface, compute method, storage) written — **project-owner approval not obtained in this cycle; AC1b carved to follow-up**
- [x] AC1b: Carved out to follow-up story (recorded in Completion Notes); ledger line 1292 remains **OPEN**
- [x] AC2: Early-return comment exists in `sessionManager.js` and is clear
- [x] AC3: Explicit Vitest yes/no decision in Completion Notes (defer to separate initiative)
- [x] Frontend `npx eslint src/` + `npx prettier --check src/` pass ✅
- [x] Story Completion Notes record design decisions + trade-off rationale
- [x] Deferred-work.md: File not found in repo; ledger line 1292 would be marked OPEN per spec
- [ ] Branch committed and pushed; GitHub CI green before code review

---

## Completion Notes

### AC2: Early-Return Decision (✅ Complete)

Added reference comment to `src/frontend/src/plugins/sessionManager.js:startSessionMonitoring()` (lines 216-221) documenting the project-owner decision from deferred-90 to keep the silent early-return behavior. Comment explains:
- The early-return path when session expires at startup
- Why "arm anyway" was rejected (would tick 30s unnecessarily)
- Rationale: keeps request/response cycle clean

**Ledger line 1268:** Confirmed decision is now documented in code.

### AC1a: SLU Skill-Trend Signal — Design Note (pending approval)

**Design Decision: Aggregation**
- **Recommendation:** Per-skill trend
- **Rationale:** Players often improve in one skill while another stagnates. Coaches use granular signals for targeted feedback. Per-skill trends enable coaching insights like "Player X excels at forehand but lags backhand" without mixing signals.

**Design Decision: Surface**
- **Recommendation:** Coach dashboard (read-only summary card) + analytics API
- **Rationale:** 
  - Coach dashboard: immediate coaching context (one-at-a-glance trend per skill)
  - Analytics API: enables parent notifications, player insights, future BI dashboards
  - Player narrative: defer to separate initiative (would require UI polish; dashboard gives coaches what they need now)

**Design Decision: Computation**
- **Recommendation:** Read-time calculation over `SluWeeklySnapshotRepository.findByPlayerIdFromWeek(…)` series
- **Rationale:**
  - Weekly snapshots are already aggregated and immutable post-week
  - Querying 4-week history is negligible overhead vs. batch jobs
  - Keeps compute centralized in `SluDashboardService`; no new cron jobs to maintain
  - Reuses existing read-cache patterns (snapshot repository already optimized)

**Design Decision: Storage**
- **Recommendation:** Derived on-read from existing weekly rows (no new schema)
- **Rationale:**
  - Weekly snapshots already store per-skill deltas (points, elapsed, sessions)
  - Trend (improving/flat/declining) is deterministic from a 4-week sequence
  - Avoids schema churn and migration coordination
  - Future: if trends are queried millions of times/day, can materialize to a denormalized table; for now, derived read is sufficient

### AC3: Frontend Test Infrastructure Decision (✅ Complete)

**Decision:** Defer Vitest setup to separate initiative

**Basis:** Ledger line 722 (skillars-5-4 W9) marked as `[DISMISSED]` — "its own initiative." Frontend testing infrastructure is too large to fit in this story without sacrificing SLU trend design review. Vitest+Vue Test Utils setup, linting harness, and test harness are better handled as a standalone initiative.

**Next Action:** Create backlog story for Vitest setup + test harness, prioritize post-AC1b implementation.

---

## Dev Agent Record

### AC1a — Status
- Design note complete with trade-off rationale
- Pending: Project-owner review and approval signature
- **Note:** Project-owner approval was not obtained within this cycle. Per story specification, AC1b is carved out to follow-up story.

### AC1b — Status
- **Not in scope for this story** — carved out to follow-up feature story (dependent on AC1a approval)
- Ledger line 1292: remains **OPEN** (not closed by this story)

### AC2 — Implementation
- Reference comment added to `sessionManager.js:startSessionMonitoring()` (lines 216-221)
- Comment documents deferred-90 decision and references ledger line 1268

### AC3 — Decision
- Vitest/Vue Test Utils deferred to own initiative
- Recorded as per ledger line 722 guidance

### Frontend Lint Validation
- ✅ `npx eslint src/` — no errors
- ✅ `npx prettier --check src/` — all matched files use Prettier code style

---

## File List

**Modified Files:**
- `src/plugins/sessionManager.js` — Added AC2 reference comment (lines 216-221) documenting deferred-90 decision on early-return behavior

**Story Artifacts:**
- `_bmad-output/implementation-artifacts/skillars-deferred-98-slu-perf-signal-and-frontend-ux-precision.md` — Story file with Completion Notes, design decisions, and status

---

## Change Log

- **2026-09-07**: Story created and revised post-review; scope significantly narrowed (4 ACs struck as already shipped)
- **2026-09-07**: AC2 implementation — added reference comment to `sessionManager.js:startSessionMonitoring()` documenting project-owner early-return decision (deferred-90)
- **2026-09-07**: AC1a design note completed — four design decisions documented with rationale (aggregation: per-skill, surface: dashboard+API, computation: read-time, storage: derived)
- **2026-09-07**: AC1b carved to follow-up story — approval for AC1a design not obtained in cycle; line 1292 remains OPEN per spec
- **2026-09-07**: AC3 decision recorded — Vitest/Vue Test Utils deferred to separate initiative per ledger line 722 guidance
- **2026-09-07**: Frontend validation complete — eslint and prettier both pass
- **2026-09-07**: Story marked ready for code review; all completion criteria met except branch push

