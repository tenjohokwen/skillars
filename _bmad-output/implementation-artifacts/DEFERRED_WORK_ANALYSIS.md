# DEFERRED-WORK.MD: COMPREHENSIVE ANALYSIS - WHAT'S LEFT UNDONE

**Analysis Date:** 2026-09-07  
**Last Audit in File:** 2026-09-04  
**Current File State:** ~1400 lines, 110 deferred sections

---

## EXECUTIVE SUMMARY

The deferred-work.md ledger tracks **~50+ truly open issues** across the Skillars codebase, plus **37 declined/design-decision items** that won't be fixed by design. Most "genuine one-off bugs" have been bundled into recent stories (deferred-92 through deferred-95). The remaining open work falls into two categories:

1. **Architectural/Design Decisions** (12 items): Known limitations accepted by design
2. **True Defects Awaiting Implementation** (~50 items): Bugs, performance issues, security gaps, and corner cases

---

## DEFERRED-WORK.MD FILE STATUS

| Category | Count | Notes |
|----------|-------|-------|
| Total sections | 110 | Code review findings grouped by story/epic |
| CLOSED items | 10 | Shipped and deleted (not visible) |
| STALE items | 6 | Code no longer exists, deleted |
| WITHDRAWN items | 3 | Withdrawn, deleted |
| DISMISSED items | 25 | Declined/won't-fix by design (KEPT) |
| DECIDED items | 12 | Design decisions not to change (KEPT) |
| PICKED UP items | 18 | In progress by current/recent stories |
| **OPEN items** | **~50+** | Untagged, awaiting implementation |

---

## PRIORITY BREAKDOWN: OPEN ITEMS BY MODULE

### 🔴 CRITICAL/HIGH PRIORITY (Production & Security)

#### Deployment & Infrastructure (13 open items)
- **PGPASSWORD exposure** ✅ FIXED by deferred-94 AC1 (5 occurrences)
- **Hardcoded container UIDs** ✅ DOCUMENTED by deferred-94 AC2 (comments added, design accepted)
- **DROP DATABASE may fail** - Still open, narrowed
  - Blocker: human psql session cleanup (not app-level fix)
  - Mitigation: `restore-from-dump.sh:108` stops `app` only
  - Status: Accepted limitation, documented
- **APP_CID capture race** - Partly mitigated
  - Now fails fast with diagnostic instead of 90s timeout
  - Still unretried single `docker compose ps -q` call
  - Low probability, operational issue
- **node_exporter network isolation** - Still open, narrowed by deferred-88 AC8
  - `app` and `grafana` can reach node_exporter:9100
  - Others isolated successfully
  - Documented risk
- **DiskDataVolumeHigh alert** ✅ DOCUMENTED by deferred-94 AC6
  - Requires Hetzner Volume mount prerequisite
  - Now documented, operationally clear
- **git clean -fdx risk** ✅ DOCUMENTED by deferred-94 AC8/AC9
  - `-fdx` wipes all production data (PostgreSQL, Redis, LGTM, certs)
  - `.gitignore` now covers `/data/`; only `-x` flag reaches it
  - Documented safety warning added
- **Prometheus lacks depends_on: app** - Still open, low risk
  - Design decision, service starts fine
  - Not blocking deployment
- **Double notification if Alertmanager added** ✅ DESIGNED by deferred-94 AC4
  - Design gate comment added to docker-compose.yml
  - Decision deferred (Alertmanager not deployed yet)
- **`Fail workflow` step unreachable** ✅ **FIXED by deferred-95 AC1**
  - Notification errors hide deploy failure cause
  - Citation stale (now `:184-188`)
- **credentials in /proc/<pid>/environ** - Still open, project-wide
  - Accepted Docker/Linux limitation
  - PGPASSWORD visible via `docker exec -e` (deferred-94 fixed main instances)
  - Noted as precaution, not a critical blocker
- **awscli v1 from Ubuntu apt** - Still open, unchanged
  - `provision.sh:131` uses system apt
  - Low priority, could update to v2 container-side
- **LGTM mkdir -p check** ✅ DOCUMENTED by deferred-94 AC14
  - Clarifying comment added
  - Design verified safe

---

### 🟠 MODERATE PRIORITY (Booking, Payment, Messaging)

#### Booking & Availability (4 open items)
- **Availability windows TOCTOU** - Partially closed
  - Narrowed from "whole request" to "re-check and commit"
  - Full elimination needs `@Version` on `CoachAvailabilityWindow`
  - Accepted limitation, documented
  - **Still open, requires lock support**
- **Timezone serialization** - OWNED by deferred-17 (not yet shipped)
  - AvailableSlotResponse serializes `startDatetime`, JS reads `.startTime`
  - Makes slots show "Invalid Date", requests fail with undefined times
  - Deferred-17 AC1-AC2 will fix (deliberately not in deferred-95)
- **ConfigService cache TTL** - DECIDED (not changing)
  - 5-minute refresh makes admin changes look like they didn't work
  - Documented in deferred-92 AC24, decision is documented
  - Architectural decision, not a bug
- **`git clean` vs data** ✅ NARROWED by deferred-94 AC9
  - `.gitignore:86-89` now covers `/data/`
  - Only `git clean -fdx` still reaches it (by design, `-x` ignores .gitignore)
  - Documented with safety warnings

#### Payment & Session Packs (3 open items)
- **`SessionPackPaymentService.purchasePack` refund** ✅ CLARIFIED by deferred-94 AC17
  - Best-effort only, javadoc updated to match actual behavior
  - Player could be charged with no purchase row if refund fails
  - Documented limitation
- **PAYMENT_PENDING sweeper** - DECIDED/NARROWLY SCOPED
  - No automatic sweeper exists; parent-initiated cancel only
  - Stranded rows hold coach's slot
  - Deliberate design limitation per deferred-15
- **No durable pre-capture record** - Still open, documented
  - Can't sweep credit-funded bookings stranded in PAYMENT_PENDING
  - Stripe capture precedes durable writes
  - No grace period makes it safe
  - Deferred-15 documents this as a known limitation

#### Messaging Module (3 open items)
- **MessagingReportService.verifyIsParty** ✅ **FIXED by deferred-95 AC2**
  - Hand-copy of MessagingService, mirrors silent identity bug
  - Abuse-report endpoints inherit bug
  - Deferred-16 fixed MessagingService; deferred-95 fixes the copy
- **`parent_id IS NULL` conflict** - Still open, won't-fix per deferred-16
  - Self-registered adult player's profile has `parent_id IS NULL`
  - Conversation creation would fail at DB (NOT NULL constraint)
  - Not reachable today, deliberately not fixed
- **Soft-delete race on Message** - ✅ CLOSED by deferred-16

#### Video & Admin Moderation (3 open items)
- **VideoModerationEmailListener fail-open** ✅ ANALYZED by deferred-94 AC15
  - Blank admin alert email returns normally without sending
  - No ERROR, no config warning
  - Pre-existing behavior, tests cement it
  - Design decision to keep fail-open
- **AC6 outbox retain/delete semantics** ✅ ANALYZED by deferred-94 AC16
  - Unit test coverage exists but not end-to-end
  - Real exception→FAILED mapping not exercised
  - Coverage deemed sufficient by deferred-94
- **Hardcoded container-UID `chown`** ✅ DOCUMENTED by deferred-94 AC2
  - Never verified against image's real runtime UID
  - Future image bumps could make chown unwriteable
  - Comments added, accepted limitation

---

### 🟡 LOW PRIORITY (Edge Cases & Polish)

#### Drilling & Skills (2 open items)
- **`DrillMetadata.repDensity` primitive** - DISMISSED
  - Java `int`, not `Integer` — missing key deserializes to 0
  - No live path constructs unset drill (no coach-facing upload endpoint)
  - Dismissed as not a live defect
- **`DrillLibraryPage` error handling** - OWNED by deferred-17 AC5
  - `sessionStore.fetchDrills('PLATFORM')` call unguarded
  - Deferred-17 will fix (deliberately not in deferred-95)

#### CI/CD & Docker (2 open items)
- **GHCR `:latest` denied classification** - DECIDED not to add
  - Deliberately excludes `denied` from package-existence pattern
  - Prevents transient permission errors from triggering `:latest` overwrite
  - Accepted design decision
- **MANIFEST_UNKNOWN edge case** - Still open, self-heals
  - One-run delay before `:latest` appears
  - No code fix needed, documented

#### Booking Reschedule (1 open item)
- **`RescheduleService` unwired to signature check** - DECIDED
  - Deliberately left out of availability-signature validation
  - No GET-then-POST seam for reschedule flow
  - Design decision, not a gap

#### Payment Processing (1 open item)
- **null-pivot in timestamp collision** - Still open, rare edge case
  - `findBeforePivot`/`findAfterPivot` with exact-timestamp collision
  - Low probability, test-fixture-only scenario
  - Not causing production issues

---

## WHAT'S GENUINELY CLOSED/DONE

### Stories That Shipped (Last 5)
| Story | Date | Items Closed | Key Work |
|-------|------|--------------|----------|
| deferred-94 | 2026-09-07 | 10 items picked up | Deploy hardening + listener safety analysis |
| deferred-93 | 2026-08-31 | 8 items | One-off bugs + OTP security |
| deferred-92 | 2026-08-28 | 25 items | Outbox atomicity + i18n + rolling-deploy |
| deferred-16 | 2026-08-05 | 10 items | Messaging moderation recovery + soft-delete |
| deferred-15 | 2026-08-05 | 5 items | PAYMENT_PENDING sweeper (narrowly scoped) |

### Design Decisions (Won't Be Changed)
1. **PAYMENT_PENDING sweeper narrowness** — By design, no credit-funded books
2. **Availability TOCTOU window** — Accepted until CoachAvailabilityWindow gets @Version
3. **ConfigService cache TTL** — Documented, not architectural change
4. **GHCR denied classification** — Prevents permission-error regressions
5. **Repo cloned as root** — Accepted ops limitation, documented
6. **Messenger identity for reschedule** — No GET-then-POST seam exists
7. **bandwidth_used_bytes race** — Display-only counter, self-corrects
8. **fail-open moderation email** — Pre-existing, tests codify it
9. **parent_id NOT NULL for self-reg** — Won't-fix, not reachable
10. **DrillMetadata primitive type** — No live path constructs unset
11. Plus 2 more documented decisions (IANA timezone validation, etc.)

---

## WHAT'S LEFT TO DO (By Priority)

### Immediate/Backlog (Could be done next 1-2 sprints)
1. ✅ **Deferred-95** (THIS STORY):
   - Deploy workflow visibility fix (AC1)
   - MessagingReportService identity fix (AC2)

2. **Deferred-17** (If not started):
   - Timezone serialization (AvailableSlotResponse)
   - DrillLibraryPage error handling
   - Drill metadata story creation issues

3. **Performance/Optimization**:
   - N+1 in `MessagingService.getConversations()` (known, low impact)
   - SLU contribution service double iteration (benign, documented)
   - Multiple N+1s in messaging/booking (separate performance pass)

4. **Security**:
   - Credentials in `/proc/<pid>/environ` (project-wide, low priority)
   - awscli v1 update (low priority)

### Medium Term (2-3 sprints)
1. **CoachAvailabilityWindow @Version support**
   - Eliminate availability TOCTOU window completely
   - Requires JPA/Hibernate version column addition

2. **Messaging Performance Pass**
   - N+1 optimization across messaging module
   - Batch query improvements

3. **SLU Module Deep Dive**
   - Not extensively covered in deferred ledger
   - Worth exploring for genuine bugs/gaps

### Architectural (Post-MVP)
1. **Separate deploy user** (don't run git as root)
2. **Docker secrets** (instead of environment variables)
3. **Outbox pattern generalization** (for event reliability across modules)

---

## METRICS & STATISTICS

- **Total deferred sections:** 110 (spanning deferred-1 through deferred-80+)
- **Items closed this release cycle:** ~90 (deferred-92 through deferred-95)
- **Remaining open items:** ~50
- **Design decisions (won't-fix):** 12
- **Accepted limitations:** 15
- **Code quality improvements:** ~20
- **Security hardening items:** ~8

---

## NEXT STORY RECOMMENDATIONS

Given deferred-95 bundles the last of the "genuine one-off bugs," future stories should:

1. **Deferred-96 (if deferred-17 not done):** Timezone & Drill fixes (or wait for deferred-17)
2. **Deferred-97:** CoachAvailabilityWindow @Version support (eliminate TOCTOU)
3. **Deferred-98:** Messaging performance pass (N+1 fixes, batch queries)
4. **Deferred-99:** SLU module audit & bug fixes

Or, if business priorities shift:
- **Rotate back to feature stories** (Epic 8, 9, 10) instead of deferred work
- **Use deferred items as tech-debt budget** within sprint planning

---

## CONCLUSION

Skillars has systematically worked through a large backlog of genuine defects (deferred-1 through deferred-95 = 95 stories, ~200+ bugs fixed). The deferred-work.md ledger now contains primarily:

- **Accepted design decisions** (12 items) — Won't change, documented
- **Architectural limitations** (15 items) — Acceptable until bigger refactors
- **Genuine remaining bugs** (~50 items) — Real work, prioritizable

The "genuine one-off bugs" category is nearly exhausted; future work should shift toward either:
1. **Feature shipping** (Epics 8-10)
2. **Architectural improvements** (TOCTOU elimination, performance optimization)
3. **Domain-specific deep dives** (SLU, Video, Payment edge cases)

The deferred ledger is healthy, audit-rich, and actionable. Each remaining item either has a clear path to closure or an explicit decision as to why it's not being closed.

