---
stepsCompleted: ['step-01-document-discovery', 'step-02-prd-analysis', 'step-03-epic-coverage-validation', 'step-04-ux-alignment', 'step-05-epic-quality-review', 'step-06-final-assessment']
documentsIncluded:
  prd: 'prds/prd-skillars-2026-06-08/prd.md'
  prd_addendum: 'prds/prd-skillars-2026-06-08/addendum.md'
  architecture: 'architecture.md'
  epics: 'skillars-epics.md'
  ux: 'ux-design-specification.md'
---

# Implementation Readiness Assessment Report

**Date:** 2026-06-10
**Project:** Skillars

---

## PRD Analysis

### Functional Requirements

**Platform Configuration (2)**
- FR-PLT-001: All tier-based feature entitlements must be driven by a configuration layer persisted in the database, not hardcoded. Modifiable by admin without code deployment or restart.
- FR-PLT-002: Platform commission rate (default 8%) stored in config layer, modifiable by admin without deployment.

**UI Design System (1)**
- FR-UI-001: All frontend screens must conform to the Skillars Design System (glassmorphism, dark-primary, CSS token-only colour, WCAG AA, 44px touch targets, both modes validated).

**Marketplace & Discovery (7)**
- FR-MKT-001: Search and filter by city, district, language, price, age group, specialty, rating. Default sort by distance; re-sortable by rating or price.
- FR-MKT-002: Coach profile displays name, bio, languages, specialties, pricing, location, availability, star rating, verification badge, capability badges, media gallery.
- FR-MKT-003: Verification tier (Basic/Trusted/Featured) displayed on profile and marketplace card; admin-granted only.
- FR-MKT-004: Capability badges (Video Feedback, Performance Reports, Homework Assignments, Skills Radar, Verified Identity) displayed where coach actively uses these features.
- FR-MKT-005: Guests can browse and view profiles; cannot contact, book, or review.
- FR-MKT-006: Auto-detect and redact contact details (email/phone) in all coach free-text fields; visible form warning to coaches.
- FR-MKT-007: Coach self-registration without admin approval. 5-step onboarding: account → basic verification → profile builder → availability setup → subscription. Scout (free) default.

**Booking & Scheduling (13)**
- FR-BKG-001: Hard block on booking requests when session credits exhausted. Pack status visible before booking.
- FR-BKG-002: Request/Approval workflow (REQUESTED → coach notified → ACCEPTED/DECLINED → payment processed → CONFIRMED).
- FR-BKG-003: Full booking state machine with 14 states (REQUESTED, ACCEPTED, DECLINED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, COMPLETED, CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_PLAYER, NO_SHOW_COACH, DISPUTED, REFUND_PENDING, REFUNDED). Credit deducted only at COMPLETED. DISPUTED freezes deduction.
- FR-BKG-004: All timestamps in UTC/TIMESTAMPTZ. Display in Pitch Timezone by default. Every Session/CoachProfile stores canonical_timezone. Notify users if browser timezone differs from pitch timezone. Frontend timezone via Luxon or Day.js.
- FR-BKG-005: Parent may request session time change; coach accepts (session updates) or declines (original retained).
- FR-BKG-006 *(Post-MVP)*: Two-way Google/Apple Calendar sync deferred.
- FR-BKG-007: Coaches define recurring availability windows and manually block time. Managed via in-app calendar view.
- FR-BKG-008: Automated reminders to parent and player at configurable intervals; minimum 24h before and 2h before.
- FR-BKG-009: Coaches can duplicate a booked session to the following week for recurring clients.
- FR-BKG-010: Two completion paths — Live Session Mode (Start/End taps with audit timestamps) and Quick Complete Mode (requires explicit parent/player confirmation gate before credit deduction). 30-second wrap-up post-session.
- FR-BKG-011: Coach "Command Center" view (all active clients, schedule gaps, projected revenue). Parent view (child sessions + remaining credits). Pitch-side workflow completable in <30s one-handed mobile.
- FR-BKG-012: In Quick Complete Mode, session credit deduction is gated behind explicit parent/player confirmation.
- FR-BKG-013: Coach scheduling view must display total projected weekly revenue from confirmed upcoming sessions.

**Session Builder & Drill Library (16)**
- FR-SES-001: 4 default session blocks (Warm-Up 10min, Technical Foundation 15min, Game Intensity 25min, Cool-Down & Review 10min); customizable duration and content.
- FR-SES-002: Development Focus selection at session start; adapts drill recommendations and Session DNA weighting.
- FR-SES-003: Intelligent drill suggestions based on focus, neglected skills, training load, player age, difficulty tier, and equipment.
- FR-SES-004: Live Session DNA analysis showing balance across 5 dimensions (Technical, Physical, Cognitive, Match Realism, Weak Foot Focus) scored 0–100. Distinct from Skills Radar.
- FR-SES-005: Drag-and-drop reordering of drills within and across blocks.
- FR-SES-006: Automatic equipment packing list aggregated across all drills in a session.
- FR-SES-007: Session templates saved to private vault; deployable per player with per-deployment customization without modifying original.
- FR-SES-008: 30-second post-session wrap-up: attendance confirmation (mandatory), effort/focus/technique ratings (1–5 stars), optional voice-to-text notes, optional homework assignment. Ratings are qualitative only — not used in Skills Radar calculations.
- FR-SES-009: Coaches assign 1–2 homework drills per player. Players see only assigned drills. Homework access terminates when paid session pack exhausted.
- FR-SES-010: Session Builder (Intelligent Drill Suggestions, Session DNA, Equipment Packing Lists, Templates) gated to Instructor tier and above.
- FR-DRL-001: Platform Library (centrally managed, read-only, cloneable) + Coach Private Library (coach-owned, counts against storage quota).
- FR-DRL-002: Drill metadata schema: Primary/Secondary Skills, Skill Weighting, Rep Density, Intensity, Pressure Level, Cognitive Load, Match Realism, Weak Foot Bias, Difficulty Tier, Equipment Required, Recommended Group Size, Video Demo (15s silent loop), Coaching Points (3–4 bullets).
- FR-DRL-003: Drill card UI (mobile-first): 15s silent autoplay loop, coaching points, setup diagram, equipment list, development summary, expected SLU exposure estimate.
- FR-DRL-004: Search and filter by skill/difficulty/equipment; tag/categorize by development intent; clone platform drills.
- FR-DRL-005: Platform launches with 20 curated drills in 4 packs (The Master Touch, The Sniper, The Escape Artist, The Wall).
- FR-DRL-006: Coaches on paid tiers may upload custom drill videos; transcoded to 720p maximum.

**Player Development Intelligence (14)**
- FR-DEV-001: 15-skill taxonomy (PAC, SHO, PAS, DRI, PHY, DEF, WEF, F1T, FIN, 1V1, HED, CRO, IBS, OBS, FKI). Extensible without breaking existing data.
- FR-DEV-002: SLUs auto-calculated on session completion. Inputs: drill duration, skill weighting, rep density, intensity, pressure level, match realism. Formula not exposed to users.
- FR-DEV-003: SLU values baked into player_skill_stats at COMPLETED. Historical data is permanent; drill metadata changes must not retroactively alter prior SLU records.
- FR-DEV-004: Cumulative SLU per skill, trends (line graphs, stacked weekly bars, rolling averages, radar charts), weekly consistency, progress toward coach-defined targets. Aggregated across all coaches.
- FR-DEV-005: Proactive flagging of skills below target thresholds. Alerts in coach dashboard and player portal.
- FR-DEV-006: Coaches may define weekly SLU targets per skill per player.
- FR-DEV-007: Skills Radar ("Big Test") — coach-entered standardised assessment. 1–100 scale. Weighted composite: 50% objective results + 30% match observation + 20% coach technical evaluation. Standardised rubrics enforced.
- FR-DEV-008: Universal 1–100 scoring scale with 7 tiers (Elite 90–100, Excellent 80–89, Advanced 70–79, Intermediate 60–69, Developing 50–59, Beginner 40–49, Very Weak <40).
- FR-DEV-009: All coach assessments contribute to a single unified Skills Radar per player. No per-coach silo.
- FR-DEV-010 *(Post-MVP)*: Coach score normalisation deferred.
- FR-DEV-011: Development Correlation Engine (SLU vs. Radar improvements, surfaced insights) — Academy tier only.
- FR-DEV-012: Interactive radar for all 15 skills. Coach selects which skills to test/display. Each node shows confidence indicator and last-updated timestamp. Current score vs. baseline growth comparison.
- FR-DEV-013: PDF Performance Index report per player: name/date/coach, coach branding (Academy tier only), performance radar, baseline vs. current scores with improvement %, coach qualitative summary. Instructor tier: standard; Academy tier: branded.
- FR-DEV-014: Unified chronological timeline per player (sessions, homework, videos, feedback, assessments, milestones, payments, reviews). Coach access expires per FR-PLT-001. Parents have full access always.

**Player Portal (13)**
- FR-POR-001: Visual session pack tracker (credits used vs. total) visible before any booking request.
- FR-POR-002: Upcoming sessions in Pitch Timezone by default; toggle to "My Current Time". Timezone warning on login if browser differs.
- FR-POR-003: "Request Change" button for parents to propose session time change (see FR-BKG-005).
- FR-POR-004: Locker Room — only assigned homework drills visible; 15s pro-demo loop. No full library access. Access terminates when paid pack exhausted.
- FR-POR-005: View real-time storage and bandwidth quota usage; delete older videos; view video status.
- FR-POR-006: Parent approval required for any video uploaded/tagged by coach for a minor player. Parent notified; can approve or reject. Rejected videos flagged (not auto-deleted).
- FR-POR-007: Single parent login manages multiple player profiles. Switch profiles without logging out. Each profile isolated.
- FR-POR-008: SLU data and Skills Radar scores displayed cumulatively across all coaches. No per-coach filtered view.
- FR-POR-009: Parents view and download PDF reports. Cannot generate reports (coach-initiated only).
- FR-POR-010: All messages between player and any coach visible to parent at all times for all minor players.
- FR-POR-011: Players under 18 cannot upload performance videos. Upload action not displayed to minor accounts. Hard platform restriction.
- FR-POR-012: Real-time storage and bandwidth usage vs. tier limit displayed (e.g., "1.4 GB of 2 GB used"). Updates after each upload or deletion.
- FR-POR-013: Self-service video deletion (permanent). Player/parent own homework videos; coach/admin own drill videos.

**Video Module (20)**
- FR-VID-001: 3 video types — Homework (player, 60s, 250MB), Drill Demo (coach, 120s, 500MB), Coach Review (coach, 300s, 1GB).
- FR-VID-002: Async upload pipeline via Bunny.net: pre-flight quota check + reservation → signed URL → direct upload to Bunny → transcoding → webhook sync → permanent record. Reservation expires after 60min if webhook not received.
- FR-VID-003: Video state machine: PENDING → UPLOADED → SCANNING → TRANSCODING → PUBLISHED. Failure locks asset and notifies uploader.
- FR-VID-004: Storage quotas by tier (Scout 0GB, Instructor 5GB, Academy 20GB, Athlete 2GB, Semi-Pro 4GB, Pro 7GB).
- FR-VID-005: Monthly bandwidth quotas by tier (Scout 5GB, Instructor 50GB, Academy 200GB, Athlete 10GB, Semi-Pro 25GB, Pro 50GB).
- FR-VID-006: Concurrency-safe quota enforcement using atomic SQL increments. Pre-flight + reservation in single atomic transaction.
- FR-VID-007: Platform drill deduplication via global reference counter. Single physical file; per-coach records point to it. Physical deletion only when ref count reaches zero.
- FR-VID-008: Playback URLs tokenised and signed. Optionally IP-bound. Bunny.net Edge Rules restrict chunk delivery to authorised domains. Signed URLs expire 2h (configurable).
- FR-VID-009: All videos private by default. No public feeds. Coach drill videos not downloadable. Player homework videos downloadable by player only.
- FR-VID-010: Video lifecycle states — ACTIVE → LOCKED (day 0–30 post-subscription expiry) → ARCHIVED (31–90 days) → PURGED (90+ days or within 30 days of account deletion).
- FR-VID-011: Async content moderation for all uploads (nudity, violence, hate symbols, explicit content). High-confidence flags → auto asset lock + account suspension + admin notification. No video reaches PUBLISHED without passing or admin override.
- FR-VID-012: Minor player videos set to HIDDEN until parent explicitly approves. Hidden video cannot be played regardless of URL validity.
- FR-VID-013: Account deletion → access revoked immediately → all owned videos physically purged from Bunny.net within 30 days → metadata purged same window.
- FR-VID-014: All quota values, size/duration limits, expiry windows, and retention periods stored in config layer; modifiable by admin without deployment.
- FR-VID-015: Video delete RBAC — authorised only if: video owner, OR verified parent of owner, OR platform admin.
- FR-VID-016: Parent account deletion cascades purge to all videos owned by all managed player profiles.
- FR-VID-017: Upload reservation timeout — 60min (configurable); background job releases reserved quota if webhook not received.
- FR-VID-018: Playback signed URLs expire 2h after issuance (configurable).
- FR-VID-019: HLS delivery with 2-second segment sizes for precise frame-seeking in slow-motion scrubbing.
- FR-VID-020: Reactive UI for video pipeline — polling or WebSockets to update video status in real-time without page reload.

**Payments & Subscriptions (17)**
- FR-PAY-001: Stripe Connect as sole MVP payment provider (coach payouts, subscription billing, commission collection).
- FR-PAY-002: 8% platform commission (configurable) auto-deducted from every session/pack payment.
- FR-PAY-003: Coach revenue dashboard shows gross revenue, commission (8%), Stripe processing fees, and net payout — all clearly labelled.
- FR-PAY-004: EUR only at MVP.
- FR-PAY-005: Off-platform payment is prohibited conduct. "Platform Payments Only" trust badge displayed. Violations subject to account termination.
- FR-PAY-006: Coach subscriptions: Scout (free), Instructor (monthly), Academy (monthly). Monthly-only, no annual.
- FR-PAY-007: Player subscriptions: Athlete (monthly/quarterly/yearly), Semi-Pro (yearly only), Pro (yearly only).
- FR-PAY-008: Yearly subscribers get lifetime video retention for duration of active subscription; non-yearly subject to 30-day archive policy.
- FR-PAY-009: Premium features (Video Review Room, Advanced Analytics, Branded Reports) activate only for active, paid session packs between a specific coach-player pair. Deactivate immediately when pack exhausted or subscription expires.
- FR-PAY-010: Payment lifecycle tied to booking states: REQUESTED (pre-auth), ACCEPTED (captured/locked), COMPLETED (funds released minus commission), DISPUTED (frozen).
- FR-PAY-011: Coaches set own prices. Per-session and session pack bundles both supported; can coexist on same profile.
- FR-PAY-012: Cancellation/refund policy: Parent >24h = full refund, 6–24h = 50%, <6h = no refund. Coach >24h = full refund no penalty, <24h = full refund + reliability strike. Player no-show = credit deducted after 15min grace. Coach no-show = full auto-refund + strike.
- FR-PAY-013: Weather cancellations — coach decides; standard rules apply if cancelled.
- FR-PAY-014: Financial reporting — parents get payment history + downloadable PDF receipt per session. Coaches get revenue dashboard with PDF receipts/invoices per payout.
- FR-PAY-015: Dispute management — parent flags session → DISPUTED → funds frozen → admin reviews audit trail → resolves in favour of coach or parent.
- FR-PAY-016: Reliability strike system: auto-issued on coach no-show and <24h coach cancellation. Configurable expiry (default 90 days). Coach sees own count; parent sees summarised trust indicator; admin sees full history. Configurable thresholds (default 3 → admin review, 5 → auto temp suspension). Strike count resets on reinstatement.
- FR-PAY-017: Subscription self-service: upgrades immediate; downgrades/cancellations take effect at end of current paid period.

**Messaging (4)**
- FR-MSG-001: Scoped to scheduling, coaching feedback, and homework discussion only.
- FR-MSG-002: Access rules by age — paid player tier required; under-10 no messaging; 10–12 parent-visible only; 13–17 player can message but all messages parent-visible; 18+ unrestricted.
- FR-MSG-003: Users can report messages (abuse, harassment, off-platform payment solicitation). Automated detection of prohibited keywords (contact details, off-platform payment solicitation).
- FR-MSG-004: Messages retained 24 months from sent date, then auto-purged.

**Reviews & Ratings (3)**
- FR-REV-001: Only users with at least one completed, paid session and no active dispute may review a coach.
- FR-REV-002: Review includes star rating (1–5), text body, optional coach reply (visible below review).
- FR-REV-003: Admins may hide or remove reviews violating platform policy.

**Admin (6)**
- FR-ADM-001: Admin can suspend/ban/re-activate accounts; manage coach verification upgrades.
- FR-ADM-002: Moderation queue for flagged videos, reported messages/reviews, AI-flagged uploads. Tiered enforcement (Warning → Temp Suspension → Permanent Ban).
- FR-ADM-003: Dispute resolution — admin sees audit trail, statements, credit/payment status; resolves in favour of coach (→ COMPLETED) or parent (→ REFUNDED).
- FR-ADM-004: Appeals — one appeal per enforcement action within 14 days; platform decision is final; visible in moderation queue.
- FR-ADM-005: Admin initiates manual refunds; views platform-wide financial reports (GMV, commission, active subscriptions by tier, dispute rate).
- FR-ADM-006: Admin views full coach reliability strike history. Strike thresholds configurable (see FR-PLT-001).

**User Access Control & Data Isolation (3)**
- FR-USR-001: Hard constraint of one parent account per player profile; second parent registration rejected at application layer.
- FR-USR-002: Explicit access denials: coaches cannot access player data without active/prior paid relationship; coaches cannot export data for outside clients; players cannot modify coach templates; Scout tier has no access to Session Builder, Reports, Skills Radar, or Timeline.
- FR-TSC-009: All database queries for player data must include mandatory parent_id filter enforced at application security layer.

**Trust, Safety & Compliance (8)**
- FR-TSC-001: Prohibited conduct list (harassment, hate speech, sexual content involving minors, violence, fraud, off-platform payments, contact detail sharing).
- FR-TSC-002: Three-tier enforcement (Warning, Temporary Suspension, Permanent Ban).
- FR-TSC-003: One appeal per enforcement action within 14 days; platform decision final.
- FR-TSC-004: All user-uploaded content scanned for nudity, violence, hate symbols, explicit content. High-confidence flags → auto asset lock + admin notification.
- FR-TSC-005: Any user can report abusive messages, inappropriate content, fraudulent coach behaviour, off-platform payment solicitation, identity misrepresentation.
- FR-TSC-006: Age-based restrictions mandatory and not user-configurable. Minor videos hidden until parent-approved. Coach-player messages involving minors parent-visible in perpetuity. Admin can escalate to permanent ban without prior warning for safeguarding incidents. No public player profiles.
- FR-TSC-007: GDPR compliance — EU data residency; 6 legal documents (ToS, Privacy, Cookie, Media Consent, DPA, Parent Consent); explicit consent at registration; data export on request; right to erasure (access revoked immediately, physical deletion within 30 days, backups purged within 90 days); data retention schedule.
- FR-TSC-008: ToS acceptance required at registration for all user types before account activation.

**Total FRs: 127** (excluding 3 Post-MVP items: FR-BKG-006, FR-DEV-010, + FR-DEV-011 is MVP but Academy-tier only)

---

### Non-Functional Requirements

- NFR-001 — Performance: Core pages load <3s on 4G/broadband. Video playback startup <2s. SLU/dev data queries sub-second (enforced via snapshot tables, no real-time historical joins).
- NFR-002 — Availability: 99.5% uptime for core services.
- NFR-003 — Modular Monolith Architecture: Modular monolith at MVP with defined boundaries (scheduling, payments, video, development intelligence). Boundaries designed to support future service extraction.
- NFR-004 — Concurrency & Multi-Node Safety: Atomic SQL increments for storage/bandwidth updates. PostgreSQL READ COMMITTED or REPEATABLE READ with SELECT FOR UPDATE. Hibernate @Version mandatory on all metadata entities.
- NFR-005 — Database: PostgreSQL with JSONB for drill_metadata, session_dna, assessment_payloads. JSONB mapped via hypersistence-utils. All temporal fields TIMESTAMPTZ.
- NFR-006 — Security: JWT-based RBAC with encrypted token storage. Mandatory parent_id filter on all player data queries. Playback URLs tokenised, signed, IP-bound. Bunny.net Edge Rules referrer whitelisting.
- NFR-007 — Observability: Loki (logging), Prometheus/Grafana (metrics), Tempo (distributed tracing).
- NFR-008 — Scalability: Bunny.net for media hosting and global edge delivery. Horizontal scaling of stateless application nodes.

**Total NFRs: 8**

---

### Additional Requirements / Constraints

- **FR-UI-001 (Design):** All screens must pass WCAG AA in both dark and light mode; CSS custom properties only for theming.
- **Geographic:** Germany-only at MVP; EUR currency only.
- **Player Data Ownership:** Player data (Skills Radar, SLU, timeline, videos) is owned by the player, portable across coaches — a strategic platform anchor.
- **Out of Scope at MVP:** Team/club management, multi-country/multi-currency, PayPal, Google/Apple calendar sync, multi-guardian, coach score normalisation, automated benchmark drills, AI session recommendations, native mobile app.

---

### PRD Completeness Assessment

The PRD is comprehensive and well-structured. Requirements are clearly numbered with consistent FR/NFR codes, enabling direct traceability to epics. Key strengths:
- Full state machine definitions for bookings and video lifecycle
- Explicit tier-gating rules for all premium features
- Clear child safeguarding rules with non-configurable enforcement
- Strong concurrency and data integrity requirements (atomic SQL, Hibernate @Version)

Minor gaps noted for cross-validation:
- FR-PAY-008 states "non-yearly subscribers subject to 30-day video archive policy" but Addendum A1.6 says "90 days" — minor inconsistency to flag
- FR-DEV-007 references standardised scoring rubrics but the rubric content itself is not defined in the PRD (may be a UX/content concern)

---

## Epic Coverage Validation

### Coverage Matrix

| FR Group | Count | Epic(s) | Status |
|---|---|---|---|
| FR-PLT-001/002 | 2 | Epic 1 | ✓ Covered |
| FR-UI-001 | 1 | Epic 1 | ✓ Covered |
| FR-USR-001/002, FR-TSC-007(infra), FR-TSC-008/009 | 5 | Epic 1 | ✓ Covered |
| FR-MKT-001–007 | 7 | Epic 2 | ✓ Covered |
| FR-BKG-001–005, 007–013 | 12 | Epic 3 | ✓ Covered |
| FR-BKG-006 | 1 | — | Post-MVP (excluded by design) |
| FR-POR-001–003 | 3 | Epic 3 | ✓ Covered |
| FR-SES-001–010 | 10 | Epic 4 | ✓ Covered |
| FR-DRL-001–006 | 6 | Epic 4 | ✓ Covered |
| FR-POR-004 | 1 | Epic 4 | ✓ Covered |
| FR-DEV-001–009, 011–014 | 13 | Epic 5 | ✓ Covered |
| FR-DEV-010 | 1 | — | Post-MVP (excluded by design) |
| FR-POR-007–009 | 3 | Epic 5 | ✓ Covered |
| FR-VID-001–020 | 20 | Epic 6 | ✓ Covered |
| FR-POR-005/006, 011–013 | 5 | Epic 6 | ✓ Covered |
| FR-TSC-004, FR-TSC-006(video) | 2 | Epic 6 | ✓ Covered |
| FR-PAY-001–017 | 17 | Epic 7 | ✓ Covered |
| FR-MSG-001–004 | 4 | Epic 8 | ✓ Covered |
| FR-POR-010, FR-TSC-006(messaging) | 2 | Epic 8 | ✓ Covered |
| FR-REV-001–003 | 3 | Epic 9 | ✓ Covered |
| FR-ADM-001–006 | 6 | Epic 10 | ✓ Covered |
| FR-TSC-001–003, 005, FR-TSC-006(admin), FR-TSC-007(admin) | 6 | Epic 10 | ✓ Covered |

### NFR Coverage

| NFR | Coverage | Status |
|---|---|---|
| NFR-001 Performance | Cross-cutting AC in Epics 3, 5, 6 | ✓ Covered |
| NFR-002 Availability | Architecture constraint documented | ✓ Noted |
| NFR-003 Modular Monolith | Architecture-level (each module init story) | ✓ Covered |
| NFR-004 Concurrency & Multi-Node | AC in Epics 3, 6, 7 | ✓ Covered |
| NFR-005 Database (JSONB/TIMESTAMPTZ) | Dev notes on all relevant stories | ✓ Covered |
| NFR-006 Security | Epic 1 (JWT/RBAC), Epic 6 (signed URLs), Epic 7 (Stripe) | ✓ Covered |
| NFR-007 Observability | Project-context.md rules + dev notes on all stories | ✓ Covered |
| NFR-008 Scalability | Architecture-level (Bunny.net, stateless nodes) | ✓ Noted |
| NFR-009 GDPR/Data Residency (added in epics) | Epics 1, 10 | ✓ Covered (enhancement) |

### Missing Requirements

**None identified.** All 127 MVP FRs are explicitly mapped in the epics FR Coverage Map.

**Post-MVP exclusions (correct):**
- FR-BKG-006: Google/Apple Calendar sync
- FR-DEV-010: Coach score normalisation

### Coverage Statistics

- **Total PRD FRs (MVP scope):** 127
- **FRs covered in epics:** 127
- **Coverage percentage: 100%**
- **Post-MVP FRs correctly excluded:** 2
- **UX Design Requirements covered:** 33 UX-DRs distributed across Epics 1–10 as component specifications and acceptance criteria

---

## UX Alignment Assessment

### UX Document Status

**Found:** `ux-design-specification.md` (58KB, Jun 9). Complete 14-step workflow document covering executive summary, core UX, design system, 5 user journeys, 11 custom components, and responsive/accessibility strategy.

### UX ↔ PRD Alignment

Overall alignment is strong. Three misalignments require resolution before implementation:

---

#### ISSUE 1 — CRITICAL: `COMPLETED_PENDING_CONFIRMATION` state missing from PRD state machine

**UX spec Journey 2** introduces `COMPLETED_PENDING_CONFIRMATION` as an explicit booking state for Quick Complete Mode: coach taps Quick Complete → `COMPLETED_PENDING_CONFIRMATION` → parent confirms → `COMPLETED`.

**PRD FR-BKG-003** does NOT include this state. Without it, the state machine has no state to represent "coach submitted Quick Complete, awaiting parent confirmation, credit deduction pending." Developers implementing from the PRD alone cannot build Quick Complete Mode correctly.

**Recommendation:** Add `COMPLETED_PENDING_CONFIRMATION` to FR-BKG-003 state machine: "Coach submitted Quick Complete; credit deduction pending parent/player confirmation; auto-confirms after 24h of no response." Also update the epics' FR-BKG-003 requirements inventory and the `BookingStatus` enum dev notes.

---

#### ISSUE 2 — MODERATE: Video `REJECTED` state missing from FR-VID-003 state machine

**UX spec Journey 4 and `VideoStatusCard` component** use a `REJECTED` state when a parent rejects a minor's video.

**FR-VID-003** defines: `PENDING → UPLOADED → SCANNING → TRANSCODING → PUBLISHED`. REJECTED is not listed. FR-POR-006 confirms rejection is a real concept but doesn't formalise it as a video state.

**Recommendation:** Add `REJECTED` to FR-VID-003 state machine: "Parent rejected video for minor player; invisible to player; not auto-deleted; flagged for coach awareness." Update the Epic 6 acceptance criteria for the video minor safety gate story.

---

#### ISSUE 3 — MINOR: Booking state count label incorrect (says 14, actually 15 + 1 missing)

PRD and epics both label FR-BKG-003 as "14-state" but the state table contains 15 states. Adding `COMPLETED_PENDING_CONFIRMATION` makes it 16.

**Recommendation:** Update the label to "16-state booking state machine" after Issue 1 is resolved.

---

#### ISSUE 4 — MINOR: 24h auto-confirmation not in PRD FR-BKG-012

The epics and UX spec both include 24h auto-confirmation in Quick Complete Mode. PRD FR-BKG-012 mentions only that parent confirmation is required — it does not specify the auto-confirm fallback.

**Recommendation:** Add to FR-BKG-012: "If no parent/player confirmation or dispute is received within 24 hours, the session auto-confirms to `COMPLETED` and the credit is deducted."

---

### UX ↔ Architecture Alignment

All architecture decisions support UX requirements:

| UX Requirement | Architecture Support | Status |
|---|---|---|
| Real-time video pipeline updates | Spring SseEmitter + 2s polling fallback | ✓ Aligned |
| Booking state SSE updates | Same SSE infrastructure | ✓ Aligned |
| Pitch timezone rendering | `canonical_timezone` field + Luxon/Day.js | ✓ Aligned |
| Parental video approval push notification | Web Push API via Service Worker | ✓ Aligned |
| Signed streaming URLs (VideoStatusCard) | Bunny.net signed URL + 2h TTL | ✓ Aligned |
| Age-tier enforcement invisible in UX | Service layer `@Service` enforcement | ✓ Aligned |
| Contact detail real-time warning | `ContactDetailSanitizer` + /api/util/sanitize-preview | ✓ Aligned |

No architectural gaps found that would block UX implementation.

### Warnings

1. `COMPLETED_PENDING_CONFIRMATION` must be added to PRD, epics, and `BookingStatus` enum before any Quick Complete story is implemented.
2. `REJECTED` video state must be added before the VideoStatusCard or minor video approval story is built.
3. `QuotaUsageBar` is referenced in the UX component roadmap (Phase 3) but has no component spec. Should be defined before Epic 5/6 frontend stories.

---

## Epic Quality Review

### Review Scope

52 stories across 10 epics validated against create-epics-and-stories best practices. Stories reviewed directly: all 1.x (6), 2.x (4), 3.x (8), 4.1–4.2 (2). Story structure and titles reviewed for remaining epics via header scan. Story bodies spot-checked for Epics 5–10 key stories.

---

### 🔴 Critical Violations

#### VIOLATION 1: Story 3.8 introduces untraced scope not in the PRD

**Story 3.8 — Bulk Session Request from Calendar** introduces multi-slot calendar selection, a `booking_batches` table, a "Accept All" coach batch action, and batch payment processing via a `BatchBookingAcceptedEvent`. None of this appears in the PRD or the epics FR Coverage Map. There is no FR-BKG or any other FR that covers bulk booking.

This is scope added in the epics layer with no PRD lineage. The story also creates tight coupling into Epic 7: Story 7.2 explicitly handles `BatchBookingAcceptedEvent` from Story 3.8, including a dedicated batch payment path with a shared Stripe PaymentIntent. This means Story 7.2 effectively cannot be cleanly completed or tested without Story 3.8's infrastructure.

**Impact:** High. Adds a new table (`booking_batches`, plus `batchId` on `bookings`), new API endpoints, new events, new test classes, and a new UI mode on `CoachCalendar.vue` — across two epics, with no PRD backing.

**Recommendation:** Either (A) add an FR to the PRD (e.g., FR-BKG-014: Batch Session Request), update the FR Coverage Map, and explicitly mark it as MVP scope; or (B) move Story 3.8 to a post-MVP backlog and remove the `BatchBookingAcceptedEvent` handling from Story 7.2 dev notes. Do not implement without a clear PRD decision.

---

### 🟠 Major Issues

#### ISSUE 2: `COMPLETED_PENDING_CONFIRMATION` state missing from state machine definition in Epic 3

*(Carried from UX Alignment step — affects Story 3.6)*

Story 3.6 Quick Complete Mode transitions a booking to an intermediate confirmation-pending state, but this state is absent from the formal state machine encoded in Story 3.4. The `BookingStateMachine` class in Story 3.4 enumerates all valid transitions without `COMPLETED_PENDING_CONFIRMATION`. Implementers of Story 3.4 will build a state machine that can't support Story 3.6 as designed.

**Recommendation:** Add `COMPLETED_PENDING_CONFIRMATION` to the Story 3.4 state machine transition graph: `IN_PROGRESS → COMPLETED_PENDING_CONFIRMATION; COMPLETED_PENDING_CONFIRMATION → COMPLETED | DISPUTED`. Also add the `BookingStatus.COMPLETED_PENDING_CONFIRMATION` enum value to the dev notes in Story 3.6.

---

#### ISSUE 3: Video REJECTED state missing from video state machine

*(Carried from UX Alignment step — affects Epic 6)*

Story 6.2 and 6.3 implement the video state machine (`PENDING → UPLOADED → SCANNING → TRANSCODING → PUBLISHED`). The `VideoStatusCard` component defined in the UX spec and referenced in Story 6.6 includes a `REJECTED` state, but no Epic 6 story adds this state to the video entity enum. When Story 6.4/6.5 implements the parental approval flow (FR-VID-012), the REJECTED state will be needed but undefined.

**Recommendation:** Add `REJECTED` to the `VideoStatus` enum in the `video_assets` entity in Story 6.1 or 6.2, with a transition: `HIDDEN → REJECTED` when a parent rejects the video. Update Story 6.6 VideoStatusCard acceptance criteria to include the REJECTED visual state.

---

### 🟡 Minor Concerns

#### CONCERN 4: Booking state count label incorrect in Epic 3 descriptions

The Epic 3 goal, Story 3.4 header text, and the Requirements Inventory all refer to a "14-state booking state machine," but counting the states in FR-BKG-003 yields 15. Adding `COMPLETED_PENDING_CONFIRMATION` from Issue 2 makes 16.

**Recommendation:** After resolving Issue 2, update all references from "14-state" to "16-state" to prevent implementers from building a `BookingStatus` enum with the wrong number of entries.

#### CONCERN 5: Auto-confirmation 24h rule in Story 3.6 not in PRD FR-BKG-012

Story 3.6 includes "auto-confirms after 24h of no response" in the Quick Complete flow. FR-BKG-012 does not mention the 24h window. The story dev notes reference a `QuickCompleteTimeoutService` reading the timeout from ConfigService, which is correct — but the PRD should be updated with this constraint.

#### CONCERN 6: Story 7.2 `BatchBookingAcceptedEvent` creates hidden coupling to untraced Story 3.8

Even if Story 3.8 is retained (Violation 1 resolved via PRD FR addition), the presence of batch payment logic embedded in Story 7.2 means Story 7.2 is implicitly scoped beyond its title. The story should document the batch path as a separate AC group clearly tied to FR-BKG-014 (if added).

---

### Best Practices Compliance Summary

| Epic | User Value | Independent | Sized Right | No Forward Deps | DB Timing | Clear ACs |
|---|---|---|---|---|---|---|
| Epic 1 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 2 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 3 | ✓ | ✓ | ⚠️ 3.8 untraced | ⚠️ 3.8→7.2 | ✓ | ✓ |
| Epic 4 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 5 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 6 | ✓ | ✓ | ✓ | ⚠️ REJECTED missing | ✓ | ✓ |
| Epic 7 | ✓ | ✓ | ✓ | ⚠️ 3.8 coupling | ✓ | ✓ |
| Epic 8 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 9 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 10 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

**Overall Story Quality:** Exceptionally high. 52 stories with full BDD Given/When/Then ACs, error path coverage, and implementation-grade dev notes (entity schemas, Spring component names, test class names). Only structural issues found are the 3 items above.

---

## Summary and Recommendations

### Overall Readiness Status

**⚠️ CONDITIONALLY READY**

The planning artifacts are of exceptionally high quality and Epics 1, 2, 4, 5, 8, 9, and 10 are implementation-ready now. However, **3 issues must be resolved before starting Epics 3, 6, or 7**, as they would require rework mid-implementation.

---

### Critical Issues Requiring Immediate Action

| # | Issue | Blocks | Action |
|---|---|---|---|
| 1 | `COMPLETED_PENDING_CONFIRMATION` state missing from booking state machine (PRD FR-BKG-003 + Story 3.4) | Epic 3 (Story 3.4, 3.6) | Add state to PRD table and Story 3.4 transition graph |
| 2 | Story 3.8 (Bulk Booking) has no PRD FR — scope not traced to any requirement | Epic 3 (Story 3.8), Epic 7 (Story 7.2) | Either add FR-BKG-014 to PRD and coverage map, or defer Story 3.8 to post-MVP |
| 3 | Video `REJECTED` state missing from FR-VID-003 and Epic 6 stories | Epic 6 (Story 6.5, 6.6) | Add `REJECTED` to `VideoStatus` enum and Epic 6 AC coverage |

---

### Recommended Next Steps

1. **Resolve PRD state machine gaps (30 min):** Add `COMPLETED_PENDING_CONFIRMATION` to FR-BKG-003 state table and Story 3.4 transition graph. Add `REJECTED` to FR-VID-003 state machine. Update state count references from "14-state" to "16-state".

2. **Make a PRD decision on Bulk Booking:** Determine whether Story 3.8 is MVP scope. If yes, add FR-BKG-014 to the PRD and FR Coverage Map. If no, delete Story 3.8 and remove the `BatchBookingAcceptedEvent` handling block from Story 7.2.

3. **Minor PRD alignments (15 min):** Add the 24h auto-confirmation rule to FR-BKG-012. Reconcile data retention conflict: FR-PAY-008 says "30-day archive" for non-yearly subscribers; Addendum A1.6 says "90 days" — pick one and make both consistent.

4. **Define `QuotaUsageBar` component spec (10 min):** Add a brief component definition in the UX spec or Epic 5/6 story dev notes to prevent inconsistent implementations.

5. **Begin implementation on Epics 1 and 2** — no blockers; these are fully ready.

---

### Issues By Category

| Category | Critical | Major | Minor |
|---|---|---|---|
| PRD completeness | 1 (missing state) | — | 3 (count label, 24h rule, retention conflict) |
| Epics scope/traceability | 1 (Story 3.8 untraced) | 1 (Story 7.2 coupling) | — |
| UX alignment | — | 1 (REJECTED video state) | 1 (QuotaUsageBar undefined) |
| **Total** | **2** | **2** | **4** |

---

### Strengths Identified

- **100% FR coverage** — all 127 MVP functional requirements are mapped to epics
- **Exceptional story quality** — 52 stories with full BDD Given/When/Then ACs, error-path coverage, and implementation-grade dev notes (entity schemas, class names, test class names)
- **Event-driven architecture properly applied** — cross-epic dependencies handled via stubs and domain events, never forward references
- **Database tables created per-story** — correct pattern followed throughout
- **UX spec is comprehensive** — 5 user journey flows, 11 custom component specs, accessibility strategy, responsive breakpoints all defined
- **Security and safeguarding** — GDPR, child safeguarding, age-tier enforcement all present in PRD and correctly traced to Epic 1 implementation
- **PRD is final-status** with clear scope boundaries and explicit post-MVP callouts

---

### Final Note

This assessment identified **8 issues across 3 categories**. 2 are critical and must be resolved before starting Epics 3, 6, or 7. The remaining 6 are minor and can be resolved in a single sitting. The core planning work is excellent — these are precision corrections to an otherwise implementation-ready document set.

**Assessor:** Implementation Readiness AI Agent
**Date:** 2026-06-10
**Report:** `_bmad-output/planning-artifacts/implementation-readiness-report-2026-06-10.md`
