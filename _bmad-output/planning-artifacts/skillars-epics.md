---
stepsCompleted: [1, 2]
inputDocuments:
  - '_bmad-output/planning-artifacts/prds/prd-skillars-2026-06-08/prd.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/ux-design-specification.md'
---

# Skillars - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Skillars, decomposing the requirements from the PRD, UX Design Specification, and Architecture into implementable stories.

---

## Requirements Inventory

### Functional Requirements

**Platform Configuration**
- FR-PLT-001: All tier-based feature entitlements stored in DB config layer (`platform_config` table); modifiable by admin without code deployment or restart. Covers: coach tier features, player tier features, all access-expiry windows, reliability strike thresholds.
- FR-PLT-002: Platform commission rate (default 8%) stored in config layer; modifiable by admin without code deployment.

**UI Design System**
- FR-UI-001: All frontend screens conform to the Skillars Design System — glassmorphism; dark mode primary / light mode accessible; CSS custom property tokens only (no hardcoded hex); Inter font weights 400–800; WCAG AA in both modes; 44px minimum touch targets.

**Marketplace & Discovery**
- FR-MKT-001: Coach search and filtering by city, district, language(s), price range, age groups, skill specialization, star rating. Default sort by distance; re-sort by rating or price.
- FR-MKT-002: Coach profile displays name, bio, languages, specialties, pricing, location, availability status, aggregate rating + review count, verification badge, capability badges, media gallery.
- FR-MKT-003: Verification tier (Basic / Trusted / Featured) displayed on profile and marketplace card; granted by admin action only.
- FR-MKT-004: Capability badges indicate which premium tools a coach actively uses (Video Feedback, Performance Reports, Homework, Skills Radar, Verified Identity).
- FR-MKT-005: Guests may browse and view coach profiles; cannot contact, book, or review.
- FR-MKT-006: Auto-detect and replace email addresses and phone numbers in all coach-provided free-text fields with a redaction notice. Visible input warning to coaches.
- FR-MKT-007: Coach self-registration 5-step onboarding without admin approval. Profile unlisted until Basic verification (email + phone OTP) complete. Scout tier is default.

**Booking & Scheduling**
- FR-BKG-001: Hard block on booking if session credits fully exhausted. Session pack status visible before booking.
- FR-BKG-002: Request/Approval workflow — parent selects slot → REQUESTED → coach accepts/declines → payment capture → CONFIRMED or DECLINED.
- FR-BKG-003: Full 16-state booking state machine (REQUESTED / ACCEPTED / DECLINED / PAYMENT_PENDING / CONFIRMED / UPCOMING / IN_PROGRESS / COMPLETED_PENDING_CONFIRMATION / COMPLETED / CANCELLED_PARENT / CANCELLED_COACH / NO_SHOW_PLAYER / NO_SHOW_COACH / DISPUTED / REFUND_PENDING / REFUNDED). Credit deduction at COMPLETED only.
- FR-BKG-004: Timezone management — all timestamps UTC (TIMESTAMPTZ); display in Pitch Timezone by default; `canonical_timezone` on every Session and CoachProfile entity; toggle to "My Current Time"; login notification if browser timezone differs.
- FR-BKG-005: Rescheduling via "Request Change" — parent proposes new time; coach accepts (auto-update) or declines (original retained).
- FR-BKG-007: Coach availability — recurring availability windows; manual blocking for personal time; managed via in-app calendar.
- FR-BKG-008: Automated reminders at configurable intervals — minimum 24h and 2h before session.
- FR-BKG-009: Session duplication — "Repeat for next week" for recurring clients.
- FR-BKG-010: Two session completion paths — Live Mode (coach taps Start/End; full session tracking) and Quick Complete Mode (post-facto; parent confirmation gate required before credit deduction).
- FR-BKG-011: Scheduling UX by role — Coach Command Center (all clients, schedule gaps, projected revenue); Parent view (child's sessions + remaining credits only). Pitch-side workflow completable in under 30 seconds, one-handed.
- FR-BKG-012: Quick Complete parent confirmation gate — session transitions to COMPLETED only after parent/player confirms; auto-confirms after 24h of no response.
- FR-BKG-013: Coach Command Center displays total projected revenue for current week from confirmed upcoming sessions alongside schedule gaps.
- FR-BKG-014: Bulk session request — parent selects up to platform-configured maximum slots (default 5) from coach calendar and submits as a grouped request; coach receives single grouped notification; Accept All or individual response; single payment transaction covers the accepted batch.

**Session Builder & Drill Library**
- FR-SES-001: Four default session blocks — Warm-Up (10 min), Technical Foundation (15 min), Game Intensity (25 min), Cool-Down & Review (10 min). Block duration and content customizable.
- FR-SES-002: Development Focus selection at session start — one or more focus areas. Drill suggestions and Session DNA weighting adapt.
- FR-SES-003: Intelligent drill suggestions based on development focus, neglected skills (SLU history), recent training load, age group, difficulty tier, equipment.
- FR-SES-004: Session DNA — live 5-dimension balance score (Technical, Physical, Cognitive, Match Realism, Weak Foot Focus), each scored 0–100. Distinct from Skills Radar.
- FR-SES-005: Drag-and-drop reordering of drills within and across session blocks.
- FR-SES-006: Equipment packing list — auto-aggregated from all drills in a session; generated on save; viewable before session.
- FR-SES-007: Session templates — save any session as reusable template in coach's private vault; deploy to individual players with per-deployment customization without altering template.
- FR-SES-008: 30-second post-session wrap-up — attendance confirmation (mandatory), quick ratings Effort/Focus/Technique (1–5 stars), optional voice-to-text notes, optional homework assignment (1–2 drills). Ratings are qualitative only — not used in Skills Radar calculation.
- FR-SES-009: Homework assignment — 1–2 drills from library assigned to specific player; player sees only their assigned drills; access terminates when session pack exhausted.
- FR-SES-010: Session Builder tier gating — available to Instructor (Pro) and above only. Scout (Free) has basic scheduling and booking only.
- FR-DRL-001: Two library types — Platform Library (centrally managed, read-only for coaches, clonable) and Coach Private Library (coach-owned drills and clones, counts against storage quota).
- FR-DRL-002: Drill metadata schema with 13 fields: Primary Skills, Secondary Skills, Skill Weighting, Rep Density, Intensity (1–5), Pressure Level (1–5), Cognitive Load (1–5), Match Realism (1–5), Weak Foot Bias, Difficulty Tier (U8→Pro), Equipment Required, Recommended Group Size, Video Demo (15s loop), Coaching Points (3–4 bullets).
- FR-DRL-003: Drill card (mobile-first) — 15-second silent autoplay loop, coaching points, setup diagram, equipment list, development summary, SLU exposure estimate.
- FR-DRL-004: Drill operations — search and filter by skill/difficulty/equipment; tag and categorize by development intent; clone platform drills into private library.
- FR-DRL-005: Foundation 20 — 20 professionally curated platform drills at launch in 4 packs (Master Touch, Sniper, Escape Artist, Wall).
- FR-DRL-006: Custom drill uploads — paid tier coaches (Instructor, Academy) may upload custom drill videos; governed by storage quota; transcoded to 720p max.

**Player Development Intelligence**
- FR-DEV-001: 15-skill taxonomy (PAC, SHO, PAS, DRI, PHY, DEF, WEF, F1T, FIN, 1V1, HED, CRO, IBS, OBS, FKI). Extensible without breaking existing data.
- FR-DEV-002: SLU automatic generation on session completion — calculates from drill duration × skill weighting × rep density × intensity × pressure × match realism modifiers. No manual rep counting. Formula is internal.
- FR-DEV-003: SLU snapshot integrity — bake SLU values into `player_skill_stats` at COMPLETED transition. Rows are immutable once written. No retroactive alteration from drill metadata changes.
- FR-DEV-004: Weekly skill exposure dashboard — cumulative SLU per skill, trends (line graphs, stacked bars, radar charts), weekly consistency, progress toward coach-defined targets. Aggregated across all coaches.
- FR-DEV-005: Neglected skill detection — proactively flag skills below target exposure; surface in coach dashboard and player portal.
- FR-DEV-006: Coach-defined SLU targets — weekly SLU targets per skill per player; auto-updated as sessions complete.
- FR-DEV-007: Skills Radar ("Big Test") — coach-entered periodic assessments using standardized rubrics. Weighted composite: objective assessment 50%, match observation 30%, coach evaluation 20%. Scores grounded in measurable evidence.
- FR-DEV-008: Universal 1–100 scoring scale with 7 tiers (Elite 90–100 through Very Weak <40).
- FR-DEV-009: Multi-coach cumulative radar — all assessment entries from all coaches contribute to one unified Skills Radar per player.
- FR-DEV-011: Development Correlation Engine (Academy tier only) — correlates accumulated SLU (volume) with Skills Radar improvements (ability); surfaces insights.
- FR-DEV-012: Radar chart display rules — interactive 15-skill radar; coach-selected subset (geometry adjusts dynamically); confidence indicator; baseline comparison; last-updated timestamp.
- FR-DEV-013: PDF Performance Report — per-player report with radar, baseline vs. current scores, improvement %, coach's "Next Steps". Standard on Instructor tier; coach-branded on Academy tier. Downloadable by parent.
- FR-DEV-014: Unified Player Timeline — chronological record of all event types (sessions, homework, videos, feedback, assessments, milestones, payments, reviews). Coach access expires after configurable inactivity period. Parent has full access at all times.

**Player Portal**
- FR-POR-001: Session pack dashboard — visual tracker showing credits used vs. total; visible before any booking request.
- FR-POR-002: Upcoming sessions view — Pitch Timezone default; toggle to "My Current Time"; system warns if browser timezone differs.
- FR-POR-003: One-tap rescheduling via "Request Change" button.
- FR-POR-004: Locker Room (Homework) — dedicated screen showing only coach-assigned drills; 15-second pro-demo loop; cannot browse full library; access terminates when session pack exhausted.
- FR-POR-005: Video management — view storage and bandwidth quota usage; delete older videos; view video status (pending approval / published / archived).
- FR-POR-006: Parental video approval workflow — parent approval required for minor players before video visible to player; notification with approve/reject; rejected videos flagged (not auto-deleted).
- FR-POR-007: Shadow account management — single parent login manages multiple player profiles; switch without logging out; profiles isolated from each other.
- FR-POR-008: Multi-coach data aggregation — SLU and Skills Radar unified across all coaches; no per-coach filtered view.
- FR-POR-009: Progress report access — parents view and download PDF reports; coach-initiated only.
- FR-POR-010: Messaging transparency — all messages between player and any coach visible to parent at all times for all minor players.
- FR-POR-011: Minor upload prohibition — players under 18 cannot upload performance videos; hard platform restriction; action not displayed.
- FR-POR-012: Real-time quota display — storage and bandwidth usage updated after each upload or deletion.
- FR-POR-013: Self-service video deletion — permanent; ownership-based RBAC (owner, verified parent of owner, or admin).

**Video Module**
- FR-VID-001: Three video types with distinct limits — Homework (player, 60s/250MB), Drill Demo (coach, 120s/500MB), Coach Review (coach, 300s/1GB).
- FR-VID-002: Async TUS upload pipeline — pre-flight quota check and reservation → direct Bunny.net upload → transcoding → `video.transcoded` webhook → metadata record + quota commit.
- FR-VID-003: Video state machine — PENDING → UPLOADED → SCANNING → TRANSCODING → PUBLISHED. Failure at any state locks asset and notifies uploader.
- FR-VID-004: Storage quotas by tier — Scout 0GB, Instructor 5GB, Academy 20GB, Athlete 2GB, Semi-Pro 4GB, Pro 7GB.
- FR-VID-005: Monthly bandwidth quotas by tier — Scout 5GB, Instructor 50GB, Academy 200GB, Athlete 10GB, Semi-Pro 25GB, Pro 50GB.
- FR-VID-006: Concurrency-safe quota enforcement — atomic SQL increments; pre-flight check and reservation in single atomic transaction using SELECT FOR UPDATE.
- FR-VID-007: Platform drill deduplication — single physical file with reference counter; atomic increment/decrement; physical deletion only when ref_count = 0.
- FR-VID-008: Streaming security — all playback URLs tokenised and signed by application server; optionally IP-bound; Bunny.net Edge Rules restrict delivery to authorised domains; 2-hour signed URL TTL.
- FR-VID-009: All videos private by default; no public video feeds; coach drill videos not downloadable; player homework videos downloadable by player only.
- FR-VID-010: Video lifecycle states — ACTIVE → LOCKED (day 0–30 post-expiry) → ARCHIVED (day 31–90) → PURGED (day 90+ or account deletion).
- FR-VID-011: Automated content moderation — async scan for nudity, violence, hate symbols, explicit content. High-confidence flag → auto-lock + account suspension pending admin review + admin notification.
- FR-VID-012: Minor safety gate — videos of minor players set to HIDDEN until parent explicitly approves; hidden video cannot be played regardless of signed URL validity.
- FR-VID-013: Account deletion purge — access revoked immediately; all owned videos + shadow account videos physically purged from Bunny.net within 30 days; metadata purged same window.
- FR-VID-014: All quota values, size/duration limits, expiry windows, and retention periods in config layer; admin-modifiable without code deployment.
- FR-VID-015: Video deletion RBAC — video owner, verified parent of owner, or platform admin only.
- FR-VID-016: Parent account deletion cascades purge to all player profiles managed under that parent.
- FR-VID-017: Upload reservation timeout — background job releases ACTIVE reservations older than 60 minutes (configurable) if webhook not received.
- FR-VID-018: Signed URLs expire 2 hours after issuance (configurable).
- FR-VID-019: HLS delivery with 2-second segment sizes for precise frame-seeking in coach review videos.
- FR-VID-020: Reactive frontend — SSE (with 2-second polling fallback) updates video state transitions without page reload.

**Payments & Subscriptions**
- FR-PAY-001: Stripe Connect as sole payment provider at MVP — coach payouts, subscription billing, marketplace commission.
- FR-PAY-002: 8% platform commission (configurable) auto-deducted from every session/pack payment before funds routed to coach.
- FR-PAY-003: Coach revenue dashboard — gross revenue, platform commission (8%), Stripe processing fees (2.9% + €0.30), net payout. All line items clearly labelled.
- FR-PAY-004: EUR (€) only at MVP.
- FR-PAY-005: Off-platform payment solicitation is prohibited conduct; platform displays "Platform Payments Only" trust badge.
- FR-PAY-006: Coach subscription tiers — Scout (free), Instructor (Pro, monthly only), Academy (Elite, monthly only). Tier prices configurable.
- FR-PAY-007: Player subscription tiers — Athlete (monthly/quarterly/yearly), Semi-Pro (yearly only), Pro (yearly only). Prices configurable.
- FR-PAY-008: Yearly subscriber players receive lifetime video retention for duration of active subscription; non-yearly subject to 30-day archive policy.
- FR-PAY-009: Premium features (Video Review Room, Advanced Analytics, Branded Reports) activate only where active paid session credits exist between a coach-player pair; deactivate immediately when pack exhausted or subscription expires.
- FR-PAY-010: Payment lifecycle — pre-authorise at REQUESTED; capture at ACCEPTED; release to coach at COMPLETED; freeze if DISPUTED.
- FR-PAY-011: Coach pricing autonomy — per-session pricing and session pack bundles coexist on same coach profile; 8% commission on both.
- FR-PAY-012: Cancellation and refund matrix — parent cancellation (>24h: full; 6–24h: 50%; <6h: none); coach cancellation (>24h: full no penalty; <24h: full + reliability strike); no-show (player: credit deducted after 15-min grace; coach: full refund + reliability strike).
- FR-PAY-013: Weather cancellations — standard refund rules apply.
- FR-PAY-014: Financial reporting — parent downloads PDF receipt per session; coach downloads PDF receipt/invoice per payout.
- FR-PAY-015: Dispute management — parent flag → DISPUTED state → funds frozen → admin reviews audit trail → resolves for coach or parent.
- FR-PAY-016: Reliability strike system — auto-issue on coach no-show or <24h coach cancellation; configurable expiry window (default 90 days); configurable thresholds (default 3→admin alert; 5→auto-suspension); strike count resets on reinstatement.
- FR-PAY-017: Subscription self-service — upgrades immediate; downgrades at end of current paid period; cancellations follow downgrade timing.

**Messaging**
- FR-MSG-001: In-platform messaging scoped to scheduling coordination, coaching feedback, and homework discussion only.
- FR-MSG-002: Age-tiered access — paid tier required; U10: no messaging; 10–12: parent-visible only, no direct player-to-coach; 13–17: player may message, all messages mandatory-visible to parent; 18+: unrestricted within scope.
- FR-MSG-003: Abuse detection and reporting — user reporting for abuse/harassment/off-platform solicitation; automated detection of prohibited keywords; reported messages queued for admin review.
- FR-MSG-004: All messages retained for 24 months from date sent; auto-purged after.

**Reviews & Ratings**
- FR-REV-001: Review eligibility — at least one completed paid session and no active dispute.
- FR-REV-002: Review structure — star rating (1–5), text body, optional coach reply visible below review.
- FR-REV-003: Admin moderation — hide or permanently remove policy-violating reviews.

**Admin**
- FR-ADM-001: Account management — suspend, permanently ban, re-activate accounts; manage coach verification upgrades (Trusted, Featured).
- FR-ADM-002: Content moderation queue — flagged videos, reported messages, reported reviews, AI-flagged uploads. Admin may delete, hide, or apply tiered enforcement (Warning → Temporary Suspension → Permanent Ban).
- FR-ADM-003: Dispute resolution — admin receives DISPUTED notifications; views session audit trail (timestamps, attendance, statements, credit/payment status); resolves for coach (→COMPLETED) or parent (→REFUNDED).
- FR-ADM-004: Appeals — users may submit one appeal within 14 days; platform decision following appeal review is final.
- FR-ADM-005: Financial oversight — manual refunds; platform-wide reports (total GMV, commission, active subscriptions by tier, dispute rate).
- FR-ADM-006: Reliability strike oversight — full coach strike history; configurable thresholds.

**User Access Control**
- FR-USR-001: One parent per player hard constraint; second parent registration rejected at application layer.
- FR-USR-002: Negative permission enforcement — coach access gated by active or previous paid relationship; no export outside own roster; players cannot modify coach session templates; Scout tier blocked from Session Builder, Reports, Skills Radar, Timeline.
- FR-TSC-009: Family-level data isolation — mandatory `parent_id` filter on ALL database queries for player data; enforced at Spring Security layer AND repository contract layer (dual layer).

**Trust, Safety & Compliance**
- FR-TSC-001: Prohibited conduct policy — harassment, sexual content involving minors, violence, fraud, off-platform payments, contact detail circumvention.
- FR-TSC-002: Three-tier enforcement — Warning (minor/first-time), Temporary Suspension (repeated/moderate), Permanent Ban (severe/minor safety breach).
- FR-TSC-003: One appeal within 14 days; final decision.
- FR-TSC-004: All user-uploaded content scanned asynchronously — nudity, violence, hate symbols, explicit, non-football inappropriate. High-confidence flag → auto-lock + admin notification.
- FR-TSC-005: User reporting — any user may report abusive messages, inappropriate content, fraudulent coach behaviour, off-platform payment solicitation, identity misrepresentation.
- FR-TSC-006: Child safeguarding — age-based restrictions mandatory and not user-configurable; minor videos hidden until parent-approved; coach-player messages involving minors parent-visible in perpetuity; admin may escalate to permanent ban without prior warning; no public-facing player profiles.
- FR-TSC-007: GDPR compliance — EU-region infrastructure only; full legal document set published (ToS, Privacy Policy, Cookie Policy, Media Consent, DPA, Parent Consent Policy); user consent at registration; data export on request; right to erasure (30-day physical deletion, 90-day backup purge); data retention schedule enforced.
- FR-TSC-008: Terms of Service acceptance mandatory at registration for all user types; no account active without acceptance.

---

### NonFunctional Requirements

- NFR-001 Performance: Core pages (Marketplace, Profiles, Portals) load under 3 seconds on standard 4G/broadband. Video playback startup under 2 seconds. SLU and development data queries sub-second (enforced via snapshot tables — no real-time historical joins).
- NFR-002 Availability: 99.5% uptime target for core services.
- NFR-003 Architecture: Modular monolith at MVP with clearly defined service boundaries (config, security, marketplace, booking, session, development, video, payment, messaging, admin). Boundaries designed to support future extraction without major refactoring.
- NFR-004 Concurrency & Multi-Node Safety: All storage/bandwidth updates use atomic SQL increments. PostgreSQL READ COMMITTED / REPEATABLE READ with SELECT FOR UPDATE where needed. Hibernate @Version mandatory on all metadata entities.
- NFR-005 Database: PostgreSQL with JSONB for `drill_metadata`, `session_dna`, and `assessment_payloads`. `hypersistence-utils` for Java type-safety. All temporal fields use TIMESTAMPTZ.
- NFR-006 Security: JWT HttpOnly + Secure + SameSite=Strict cookies (15-min access token; refresh token rotated on use). Family-level data isolation at Spring Security layer AND repository layer. Signed playback URLs (IP-bound, 2h TTL). Bunny.net Edge Rules enforce domain whitelist.
- NFR-007 Observability: Loki (centralised logging), Prometheus/Grafana (metrics), Tempo (distributed tracing). @Observed(name="...") on all @RestController resource methods.
- NFR-008 Scalability: Stateless application nodes supporting horizontal scaling. Media hosting and global edge delivery via Bunny.net.
- NFR-009 GDPR / Data Residency: All user data and video assets in EU-region infrastructure only (non-negotiable). Right to erasure cascade fully automated.

---

### Additional Requirements

**From Architecture — cross-cutting technical requirements that impact epic and story design:**

- **Starter template:** No external starter — project uses its own DDD module scaffolding convention (`com.softropic.skillars.platform.{module}` with api/service/repo/contract/config layers). Module initialization (package creation, base @Configuration, first Flyway migration, SecurityConstants entries) must be the first implementation story for each bounded context.
- **Implementation sequence locked:** platform.config → platform.security (extension) → platform.marketplace → platform.booking → platform.session → platform.development → platform.video → platform.payment → platform.messaging → platform.admin. All modules depend on platform.config before any tier gating works.
- **Flyway migration sequence:** Existing V numbers unknown; first implementation story must resolve actual V sequence by inspecting migration history.
- **platform_config seed data:** Initial seed values for all tier entitlements, quota defaults, and commission rates defined in the first implementation story for platform.config.
- **Existing module clarification:** `platform.notification` and `platform.tenant` roles need clarification — `platform.notification` may be superseded by `infrastructure.ses` + Web Push.
- **BookingService invariant:** All booking state transitions exclusively through `BookingService.transition(bookingId, BookingEvent, context)`. No other class may call `booking.setStatus()` directly.
- **SLU boundary:** `platform.session` fires `SessionCompletedEvent` only; `platform.development` owns all SLU calculation via `@TransactionalEventListener(AFTER_COMMIT)`.
- **ConfigService usage:** Always inject and call per-use — never cache in a field (stale if admin changes config mid-run).
- **Age-tier enforcement:** Service layer `@Service` only — never in `@RestController`, `@Entity`, or `@Repository`.
- **parent_id repository contract:** Every player-scoped repository method must include a `parentId` parameter — no `findByPlayerId()` without `parentId`.
- **@Transactional boundaries:** All external API calls (Stripe, Bunny.net, Gemini, Arachnid, Google VideoIntel) outside `@Transactional`. DB writes in a separate `@Transactional` after.
- **Content moderation fail-closed:** Gemini unavailable → quarantine message, do not deliver. Arachnid unavailable → hold in SCANNING, do not advance. VideoIntel fails → keep in SCANNING, alert admin, do not auto-publish.
- **Contact detail sanitization:** `infrastructure.sanitizer.ContactDetailSanitizer` is server-side security gate. Frontend `POST /api/util/sanitize-preview` is UX feedback only.
- **Webhook idempotency:** Bunny.net (HMAC-SHA256 header) and Stripe (SDK Webhook.constructEvent) webhook authentication. `processed_webhooks` table with unique constraint prevents duplicate processing.
- **Three-layer content moderation stack:** Layer 1 — Arachnid (CSAM hash, before transcoding); Layer 2 — Google VideoIntel (explicit content, after Arachnid clear, during SCANNING); Layer 3 — Gemini (chat moderation for all messages involving minors, fail-closed).
- **Real-time UI:** SSE via Spring SseEmitter (booking state + video pipeline). Exponential backoff reconnect (1s→2s→4s→max 30s) with 2-second polling fallback after 3 failed reconnects.
- **Error response format:** Always `ErrorDto` (`helpCode`, `errorMsg.key`, `errorMsg.message`, `fieldErrors`). Never `ProblemDetail`.
- **API timestamp format:** ISO 8601 UTC strings. Never epoch milliseconds. Never `ZonedDateTime` with offset in JSON.
- **Stripe Connect:** Express connected accounts for coaches; Destination Charges for commission; hosted Stripe OAuth onboarding.
- **Push notifications:** Web Push API via Service Worker (PWA). No FCM/APNS.
- **Async Outbox Pattern:** `@Scheduled` + `SELECT FOR UPDATE SKIP LOCKED` for all durable async jobs. Schedulers live in `platform.{module}.service` — never in infrastructure.
- **Frontend structure:** One API file per module (`src/frontend/src/api/*.api.js`), one Pinia store per domain (`src/frontend/src/stores/*.store.js`). No API calls outside api files. No store-to-store imports. Raw Axios calls forbidden in stores.
- **i18n:** German (`messages_de.properties`) and English (`messages_en.properties`) maintained in parallel. Module key prefixes enforced.

---

### UX Design Requirements

- UX-DR1: Role-specific entry screens — Coach lands on Command Center, Parent on Parent Portal, Player on Locker Room, Guest on Marketplace. No generic empty dashboard.
- UX-DR2: Active session screen (pitch-side) — full-screen takeover with zero navigation chrome; single primary action at a time; current drill name (42px/800), countdown timer (72px gradient), block progress pips, next drill preview, haptic transitions at block boundaries; END SESSION suppressed for first 5 minutes.
- UX-DR3: 30-second wrap-up sequence — 4-step mobile flow: attendance checkbox (mandatory) → Effort/Focus/Technique star ratings (auto-advance on 5th star) → optional voice note (skip equally prominent) → optional homework drill picker (2 most relevant); completable one-handed under 30 seconds.
- UX-DR4: Post-wrap-up summary screen — Session DNA mini-radar (5 dimensions), SLU headline breakdown (e.g., "64 weak-foot contacts · 38 first-touch reps"), "Development record updated" indicator; exits naturally to Command Center.
- UX-DR5: Coach Command Center — 3-column desktop layout (sidebar/schedule/revenue); mobile content stream with activity-first one-tap actions; projected weekly revenue, active client roster, schedule gaps all visible without navigation.
- UX-DR6: Pitch-side button specification — Start/End session buttons full-width, minimum 56px height, `--accent-primary` fill, always thumb-reachable; no navigation required to reach Start from app home.
- UX-DR7: Marketplace coach card grid — trust signals (verification tier badge, reliability score, capability badges) positioned above price, above fold, scannable in 3 seconds; 3-column desktop grid collapses to single card column on mobile; skeleton loading states.
- UX-DR8: `ReliabilityIndicator` component — displays "No reliability issues" (green) for zero strikes; "X issues (90 days)" (amber) for 1–2; "Review reliability score" (red) for 3+. Positive label always shown — no silence on zero issues.
- UX-DR9: `SkillsRadarChart` component — SVG polygon on concentric circles; per-node score badge with delta indicator ("↑ +6 since first assessment"); confidence dot; last-updated tooltip; coach-selected skill subset with dynamic geometry; accessible `<table>` with all node values for screen readers.
- UX-DR10: `SessionDNAChart` component — 5-dimension radar (Technical/Physical/Cognitive/Match Realism/Weak Foot); full-size variant (wrap-up summary) and compact thumbnail variant (session cards).
- UX-DR11: `BookingStateChip` component — maps all 14 internal states to plain-English labels with CSS token colour coding. Never exposes raw enum values (e.g., "REQUESTED" → "Awaiting coach response" in `--accent-warning`).
- UX-DR12: `SessionPackTracker` component — visual credit display with four states: Healthy (>30%), Warning (<30%, amber), Critical (1–2 left, amber + buy CTA), Exhausted (red + "Buy sessions" CTA). Always visible before booking action.
- UX-DR13: `VideoStatusCard` component — reactive card with `aria-live="polite"`; distinct visual treatment per pipeline state (spinner/moderation/progress/thumbnail/padlock/warning); SSE-driven updates without page reload.
- UX-DR14: `DrillCard` component — mobile-first; 15-second silent autoplay loop; SLU estimate chip; weak-foot bias indicator; equipment icons; 3–4 coaching point bullets; primary action (Add/Assign).
- UX-DR15: `ParentChildSwitcher` component — persistent in header; tap to reveal drawer with all family player profiles; one-tap switch with page context reload for selected player; never buried in settings.
- UX-DR16: CSS custom property colour system — all component colours via `var(--token-name)` exclusively; zero hardcoded hex values in any component; never `isDark` checks in component logic; tokens swap automatically between dark and light mode.
- UX-DR17: Glassmorphism aesthetic — glass cards with `border-radius: 28px`, `backdrop-filter: blur(18px)`, `--surface-glass` background; Inter font family weights 400–800; buttons `border-radius: 14px`, height 40/44/48px; pitch-side override minimum 56px.
- UX-DR18: WCAG AA accessibility — both dark and light modes without exception; `aria-label` on all icon-only elements; `aria-live` on all reactive content regions; semantic HTML; focus management (modal close → trigger; form submit → success/error message; wrap-up → summary heading); all flows keyboard-operable.
- UX-DR19: Mobile-first responsive layout — CSS breakpoints at 768px (tablet) and 1200px (desktop); 3-column desktop layouts collapse to single column on mobile; no hamburger menus; bottom tab navigation on all mobile role views.
- UX-DR20: Role-specific bottom tab navigation — Coach: Sessions/Clients/Library/Revenue; Parent: Sessions/Progress/Messages/Payments; Player: Locker Room/My Skills/Sessions.
- UX-DR21: Age-tier restriction UI pattern — restricted features absent from the child's UI (not blocked, not error-styled, simply invisible); parent-facing prompts positive and actionable ("Approve Luca's new video from Coach Marcus").
- UX-DR22: Subscription tier gating UI pattern — soft teaser overlay with upgrade CTA over blurred feature preview for Scout-tier restricted features; rest of page remains usable; never a full-screen block.
- UX-DR23: Timezone display — automatic Pitch Timezone rendering everywhere; non-blocking info bar on login if browser timezone differs (auto-dismisses 8 seconds); no manual user configuration needed.
- UX-DR24: Contact detail detection UX — real-time amber warning bar at top of any free-text field as user types; redacts silently on save; never a blocking error or post-submission surprise.
- UX-DR25: Empty state design — every empty state has icon/illustration + headline + contextual CTA; never a bare empty list or empty screen.
- UX-DR26: Loading states — skeleton cards matching exact real-card dimensions for grids and lists; inline `q-linear-progress` within VideoStatusCard; silent background operations (SLU, notifications, reports — no spinners).
- UX-DR27: Reduced motion support — `@media (prefers-reduced-motion: reduce)` disables all CSS transitions and animations globally.
- UX-DR28: Bilingual support — `lang="de"` / `lang="en"` attributes on appropriate content; German locale for dates (`DD.MM.YYYY`, 24h) and currency (`€65,00`); all ARIA labels in both languages.
- UX-DR29: Parent Portal development dashboard — Skills Radar with score deltas as hero; session pack tracker always visible; chronological development timeline; narrative-first presentation ("Your weak foot exposure increased 42% this month") before raw numbers.
- UX-DR30: Player Locker Room — shows only explicitly assigned homework drills (not full library); pro-demo 15-second autoplay; motivational energy framing; access terminates when pack exhausted.
- UX-DR31: Modal and overlay patterns — `q-dialog` with `border-radius: 28px` and blurred backdrop; exactly two actions (primary + ghost cancel); destructive confirmation shows explicit consequence; bottom sheets max 75% screen height with drag-to-dismiss; focus trapped while open.
- UX-DR32: Form patterns — inline validation on blur (not on submit); OTP single input with auto-advance and auto-submit on last digit; consent checkboxes never pre-checked with scrollable legal text before activation.
- UX-DR33: Feedback patterns — success: `--accent-primary` toast + checkmark, 3s auto-dismiss; error: `--accent-danger` inline at failing element + toast for async; warning: `--accent-warning` non-blocking banner; info: `--accent-secondary` toast 4s auto-dismiss.

---

### FR Coverage Map

| FR | Epic | Description |
|---|---|---|
| FR-PLT-001 | Epic 1 | DB config layer + ConfigService |
| FR-PLT-002 | Epic 1 | Configurable commission rate |
| FR-UI-001 | Epic 1 | Design system SCSS token setup |
| FR-USR-001 | Epic 1 | One parent per player hard constraint |
| FR-USR-002 | Epic 1 | Negative permission enforcement |
| FR-TSC-007 (infra) | Epic 1 | EU data residency, consent at registration, legal docs |
| FR-TSC-008 | Epic 1 | Terms acceptance at registration |
| FR-TSC-009 | Epic 1 | Family-level data isolation (dual-layer enforcement) |
| FR-MKT-001 | Epic 2 | Coach search & filtering |
| FR-MKT-002 | Epic 2 | Coach profile display |
| FR-MKT-003 | Epic 2 | Verification badges |
| FR-MKT-004 | Epic 2 | Capability badges |
| FR-MKT-005 | Epic 2 | Guest restrictions |
| FR-MKT-006 | Epic 2 | Contact detail sanitization |
| FR-MKT-007 | Epic 2 | Coach self-registration & onboarding |
| FR-BKG-001 | Epic 3 | Booking credit enforcement |
| FR-BKG-002 | Epic 3 | Request/approval workflow |
| FR-BKG-003 | Epic 3 | 16-state booking state machine |
| FR-BKG-004 | Epic 3 | Timezone management (Pitch-First Protocol) |
| FR-BKG-005 | Epic 3 | Rescheduling (one-tap RSVP) |
| FR-BKG-007 | Epic 3 | Coach availability management |
| FR-BKG-008 | Epic 3 | Automated reminders |
| FR-BKG-009 | Epic 3 | Session duplication |
| FR-BKG-010 | Epic 3 | Session completion workflows (Live + Quick Complete) |
| FR-BKG-011 | Epic 3 | Scheduling UX by role (Command Center + parent view) |
| FR-BKG-012 | Epic 3 | Quick Complete parent confirmation gate |
| FR-BKG-013 | Epic 3 | Coach Command Center projected revenue |
| FR-BKG-014 | Epic 3 | Bulk session request from calendar |
| FR-POR-001 | Epic 3 | Session pack dashboard |
| FR-POR-002 | Epic 3 | Upcoming sessions view |
| FR-POR-003 | Epic 3 | One-tap rescheduling from parent portal |
| FR-SES-001 | Epic 4 | Session block structure (four blocks) |
| FR-SES-002 | Epic 4 | Development focus selection |
| FR-SES-003 | Epic 4 | Intelligent drill suggestions |
| FR-SES-004 | Epic 4 | Session DNA analysis (5 dimensions) |
| FR-SES-005 | Epic 4 | Drag-and-drop session management |
| FR-SES-006 | Epic 4 | Equipment packing list |
| FR-SES-007 | Epic 4 | Session templates |
| FR-SES-008 | Epic 4 | 30-second post-session wrap-up |
| FR-SES-009 | Epic 4 | Homework assignment |
| FR-SES-010 | Epic 4 | Session Builder tier gating (Instructor+) |
| FR-DRL-001 | Epic 4 | Drill library architecture (platform + coach private) |
| FR-DRL-002 | Epic 4 | Drill metadata schema (13 fields) |
| FR-DRL-003 | Epic 4 | Drill card UI (mobile-first, autoplay, SLU estimate) |
| FR-DRL-004 | Epic 4 | Drill operations (search, filter, tag, clone) |
| FR-DRL-005 | Epic 4 | Foundation 20 launch content seeding |
| FR-DRL-006 | Epic 4 | Custom drill uploads (paid tier coaches) |
| FR-POR-004 | Epic 4 | Player Locker Room (homework drills) |
| FR-DEV-001 | Epic 5 | 15-skill taxonomy |
| FR-DEV-002 | Epic 5 | SLU automatic generation |
| FR-DEV-003 | Epic 5 | SLU snapshot integrity (immutable rows) |
| FR-DEV-004 | Epic 5 | Weekly skill exposure dashboard |
| FR-DEV-005 | Epic 5 | Neglected skill detection |
| FR-DEV-006 | Epic 5 | Coach-defined SLU targets |
| FR-DEV-007 | Epic 5 | Skills Radar assessment entry |
| FR-DEV-008 | Epic 5 | Universal 1–100 scoring scale |
| FR-DEV-009 | Epic 5 | Multi-coach cumulative radar |
| FR-DEV-011 | Epic 5 | Development correlation engine (Academy tier) |
| FR-DEV-012 | Epic 5 | Radar chart display rules |
| FR-DEV-013 | Epic 5 | PDF Performance Report |
| FR-DEV-014 | Epic 5 | Unified player timeline |
| FR-POR-007 | Epic 5 | Shadow account management (multi-profile switcher) |
| FR-POR-008 | Epic 5 | Multi-coach data aggregation (unified view) |
| FR-POR-009 | Epic 5 | Progress report access (parent download) |
| FR-VID-001 | Epic 6 | Video types & limits |
| FR-VID-002 | Epic 6 | TUS upload pipeline (Bunny.net async) |
| FR-VID-003 | Epic 6 | Video state machine (PENDING→PUBLISHED) |
| FR-VID-004 | Epic 6 | Storage quotas by tier |
| FR-VID-005 | Epic 6 | Bandwidth quotas by tier |
| FR-VID-006 | Epic 6 | Concurrency-safe quota enforcement |
| FR-VID-007 | Epic 6 | Platform drill deduplication (ref counter) |
| FR-VID-008 | Epic 6 | Streaming security (signed URLs, IP-bound, Edge Rules) |
| FR-VID-009 | Epic 6 | Video privacy (no public feeds) |
| FR-VID-010 | Epic 6 | Video lifecycle states (ACTIVE→LOCKED→ARCHIVED→PURGED) |
| FR-VID-011 | Epic 6 | Automated content moderation (Arachnid + VideoIntel) |
| FR-VID-012 | Epic 6 | Minor safety gate (parent approval) |
| FR-VID-013 | Epic 6 | Account deletion purge cascade |
| FR-VID-014 | Epic 6 | Configurable quota/lifecycle parameters |
| FR-VID-015 | Epic 6 | Video deletion RBAC |
| FR-VID-016 | Epic 6 | Shadow account deletion cascade |
| FR-VID-017 | Epic 6 | Upload reservation timeout |
| FR-VID-018 | Epic 6 | Signed URL expiry |
| FR-VID-019 | Epic 6 | HLS 2-second segments |
| FR-VID-020 | Epic 6 | Reactive UI for video pipeline (SSE) |
| FR-POR-005 | Epic 6 | Video management (quota display + delete) |
| FR-POR-006 | Epic 6 | Parental video approval workflow |
| FR-POR-011 | Epic 6 | Minor upload prohibition |
| FR-POR-012 | Epic 6 | Real-time quota display |
| FR-POR-013 | Epic 6 | Self-service video deletion |
| FR-TSC-004 | Epic 6 | Automated video content moderation |
| FR-TSC-006 (video) | Epic 6 | Minor safety gate for video |
| FR-PAY-001 | Epic 7 | Stripe Connect integration |
| FR-PAY-002 | Epic 7 | Platform commission (8%, configurable) |
| FR-PAY-003 | Epic 7 | Fee transparency (revenue dashboard) |
| FR-PAY-004 | Epic 7 | EUR currency only |
| FR-PAY-005 | Epic 7 | Off-platform payment prevention |
| FR-PAY-006 | Epic 7 | Coach subscription tiers |
| FR-PAY-007 | Epic 7 | Player subscription tiers |
| FR-PAY-008 | Epic 7 | Long-term data retention incentive |
| FR-PAY-009 | Epic 7 | Premium feature activation (anti-bypass) |
| FR-PAY-010 | Epic 7 | Payment lifecycle |
| FR-PAY-011 | Epic 7 | Coach pricing autonomy (per-session + bundles) |
| FR-PAY-012 | Epic 7 | Cancellation & refund policy matrix |
| FR-PAY-013 | Epic 7 | Weather cancellations |
| FR-PAY-014 | Epic 7 | Financial reporting (receipts/invoices) |
| FR-PAY-015 | Epic 7 | Dispute management (payment side) |
| FR-PAY-016 | Epic 7 | Reliability strike system |
| FR-PAY-017 | Epic 7 | Subscription self-service management |
| FR-MSG-001 | Epic 8 | Messaging scope & purpose |
| FR-MSG-002 | Epic 8 | Age-tiered messaging access |
| FR-MSG-003 | Epic 8 | Abuse detection & reporting |
| FR-MSG-004 | Epic 8 | 24-month message retention |
| FR-POR-010 | Epic 8 | Messaging transparency (parent visibility) |
| FR-TSC-006 (messaging) | Epic 8 | Gemini moderation for minor-involving threads |
| FR-REV-001 | Epic 9 | Review eligibility |
| FR-REV-002 | Epic 9 | Review structure (star + text + coach reply) |
| FR-REV-003 | Epic 9 | Review moderation (admin hide/remove) |
| FR-ADM-001 | Epic 10 | Account management (suspend/ban/reactivate/verify) |
| FR-ADM-002 | Epic 10 | Content moderation queue |
| FR-ADM-003 | Epic 10 | Dispute resolution (audit trail + resolution) |
| FR-ADM-004 | Epic 10 | Appeals (14-day window, final decision) |
| FR-ADM-005 | Epic 10 | Financial oversight (manual refunds, platform reports) |
| FR-ADM-006 | Epic 10 | Reliability strike oversight |
| FR-TSC-001 | Epic 10 | Prohibited conduct policy |
| FR-TSC-002 | Epic 10 | Enforcement tiers |
| FR-TSC-003 | Epic 10 | Appeals process |
| FR-TSC-005 | Epic 10 | User reporting queue |
| FR-TSC-006 (admin) | Epic 10 | Admin safeguarding escalation |
| FR-TSC-007 (admin tools) | Epic 10 | GDPR data export & erasure management |

**NFR coverage:** Cross-cutting — addressed as acceptance criteria in each epic (performance NFR-001 in Epics 3/5/6; concurrency NFR-004 in Epics 3/6/7; security NFR-006 in Epics 1/6/7; GDPR NFR-009 in Epics 1/10).

**UX-DR coverage:** Distributed across epics — design system tokens (UX-DR16/17) in Epic 1; marketplace components (UX-DR7/8/22) in Epic 2; session + pitch-side UI (UX-DR2/3/4/5/6/10/11/12/23/24) in Epics 3–4; development intelligence UI (UX-DR9/15/29/30) in Epic 5; video reactive UI (UX-DR13/14) in Epic 6; accessibility/responsive/i18n (UX-DR18/19/20/21/27/28) as acceptance criteria on each epic's frontend stories.

---

## Epic List

### Epic 1: Platform Foundation & Security
Coaches, parents, and players can register, log in, and access the platform. The feature gate configuration layer is operational, allowing all tier entitlements and platform parameters to be managed without code deployments. Family-level data isolation and age-tier enforcement frameworks are in place. Legal consent flows and GDPR infrastructure are established.
**FRs covered:** FR-PLT-001, FR-PLT-002, FR-UI-001, FR-USR-001, FR-USR-002, FR-TSC-007 (infra), FR-TSC-008, FR-TSC-009

---

## Epic 1: Platform Foundation & Security

Coaches, parents, and players can register, log in, and access the platform. The feature gate configuration layer is operational. Family-level data isolation and age-tier enforcement frameworks are in place. Legal consent and GDPR infrastructure are established.

### Story 1.1: Feature Gate Configuration Layer

As a platform operator,
I want all feature entitlements, quota values, and platform parameters managed from a database-backed configuration layer,
So that business rules can be updated without code deployments or application restarts.

**Acceptance Criteria:**

**Given** the application starts on a clean database
**When** the `V{n+1}__platform_config.sql` Flyway migration runs (where `n` is resolved by inspecting the existing migration history)
**Then** all required seed keys are present in `platform_config`: coach tier feature gates (Scout/Instructor/Academy capabilities), player tier feature gates (Athlete/Semi-Pro/Pro), storage quota values per tier (GB), bandwidth quota values per tier (GB/month), platform commission rate (0.08), reliability strike expiry window (90 days), strike thresholds (3 → admin alert, 5 → auto-suspension), timeline access expiry window, video signed URL TTL (7200 seconds), video reservation timeout (60 minutes), reminder intervals ([24, 2] hours), message retention period (24 months)

**Given** an admin sends `PUT /api/config/values/{key}` with a valid payload
**When** the write succeeds
**Then** the ConfigService in-memory cache is invalidated
**And** a subsequent `GET /api/config/values/{key}` returns the updated value
**And** any module calling `configService.getLong("key")` returns the new value on next invocation (within cache TTL of 5 minutes, default)

**Given** any module calls `configService.getString("key")` or `configService.getLong("key")`
**When** the cache is fresh (< TTL)
**Then** the value is returned from the in-memory cache with no database read
**And** if the cache is stale (≥ TTL), it refreshes from the database before returning
**And** no module may store a returned config value in a field — it must call ConfigService per use

**Given** a non-admin user sends `PUT /api/config/values/{key}`
**When** the request reaches the endpoint
**Then** the response is `403 Forbidden` with an `ErrorDto` payload
**And** no config value is changed

**Given** an unknown config key is requested
**When** `configService.getLong("nonexistent.key")` is called
**Then** an `IllegalStateException` is thrown (not null — fail loudly on missing config)

*Dev notes: Module `platform.config`. Entity `PlatformConfig` (key, value, valueType ENUM, description, updatedAt TIMESTAMPTZ). `ConfigService` uses `ConcurrentHashMap` cache, `@Scheduled` TTL refresh, explicit `invalidate()` on PUT. YAML: `app.config.cache-ttl-seconds: 300`. Test: `ConfigServiceTest` (unit), `ConfigResourceIT` (@SpringBootTest + Testcontainers). Resolve existing Flyway V-number in this story.*

### Story 1.2: Skillars Design System Foundation

As a user,
I want to interact with the Skillars platform through a visually consistent glassmorphism interface that works in both dark and light mode,
So that the experience is cohesive, accessible, and performant across all screens.

**Acceptance Criteria:**

**Given** the frontend SCSS is compiled
**When** any component references a colour
**Then** it uses only CSS custom property tokens (e.g., `var(--accent-primary)`, `var(--bg-primary)`) — zero hardcoded hex values exist in any `.vue` or `.scss` file
**And** the token set covers: `--bg-primary`, `--accent-primary`, `--accent-secondary`, `--accent-danger`, `--accent-warning`, `--hero-gradient`, `--surface-glass`, `--border-medium`, `--text-secondary`, `--text-disabled`, `--text-muted`

**Given** dark mode is active (default)
**When** a user toggles to light mode via the theme switcher
**Then** all token values swap automatically via CSS custom properties on `:root[data-theme="light"]`
**And** no Vue component reads `isDark` to conditionally apply colour classes — mode switching is CSS-only
**And** WCAG AA contrast ratios are met for body text and interactive elements in both modes

**Given** a `.glass-card` class is applied to any `q-card`
**When** rendered in either mode
**Then** it shows `border-radius: 28px`, `backdrop-filter: blur(18px)`, and `background: var(--surface-glass)`

**Given** any button uses the Skillars design system
**When** rendered
**Then** it has `border-radius: 14px`, minimum height 40px (standard) or 44px (form actions), and references `var(--accent-primary)` for primary fill — never a hardcoded colour
**And** the pitch-side override class produces minimum height 56px

**Given** the Inter font is loaded
**When** any text renders
**Then** only Inter is used at weights 400, 500, 600, 700, 800 with the defined scale (body 14–16px, section title 18–22px, page title 28–36px, hero metric 56–72px)

**Given** the i18n scaffold is in place
**When** any user-facing string is added to a Vue component
**Then** it must reference a key from `src/frontend/src/i18n/en/index.js` and `src/frontend/src/i18n/de/index.js`
**And** both files must be updated simultaneously — no key exists in one file but not the other

**Given** any motion/transition is defined
**When** a user has `prefers-reduced-motion: reduce` set in their OS
**Then** all CSS transitions and animations are disabled via the global media query rule
**And** no functionality is lost — only decorative motion is removed

*Dev notes: Create `src/frontend/src/css/tokens/_colors.scss` and `_typography.scss`. Wire tokens into `app.scss`. Add `data-theme` switching logic to `boot/` (no JS colour checks — only toggle the attribute). Set up `src/frontend/src/i18n/en/index.js` and `de/index.js` with module-prefixed key namespaces (`booking.*`, `video.*`, etc.). Add `prefers-reduced-motion` global rule to `app.scss`. No backend work in this story.*

### Story 1.3: Coach Account Registration & Email Verification

As a coach,
I want to create a Skillars account with email confirmation and phone OTP verification,
So that I can access the platform and begin building my professional profile.

**Acceptance Criteria:**

**Given** a visitor navigates to the coach registration page
**When** they submit name, email, password, and phone number
**Then** a new `users` record is created with role `COACH` and status `UNVERIFIED`
**And** a confirmation email is sent via AWS SES (`infrastructure.ses`) containing a one-time verification link
**And** the platform's Terms of Service and Privacy Policy checkboxes are required and must not be pre-checked
**And** if either consent checkbox is unchecked, the form cannot be submitted — the submit button remains disabled

**Given** a coach submits the registration form
**When** the registration is processed
**Then** the ISO 2-letter language code (`langKey`) is captured from the language the user has selected in the UI and stored on the user record (e.g. `"en"`, `"de"`)
**And** all transactional emails (verification link, phone OTP) are rendered via Thymeleaf templates in that language using the project's `SpringTemplateEngine` + `MessageSource` pipeline
**And** if no `langKey` is provided, `"en"` is used as the default

**Given** a coach clicks their email verification link
**When** the token is valid and not expired
**Then** their email is marked verified (`EMAIL_VERIFIED`) and they proceed to phone OTP entry
**And** if the token is expired or already used, they see a clear error with a "Resend verification email" option

**Given** a coach enters the 6-digit OTP sent to their registered email address
**When** the OTP matches and is within its 10-minute validity window
**Then** their verification status changes to `BASIC_VERIFIED` and they are redirected to the profile builder step
**And** the OTP input uses a single field with auto-advance between digits and auto-submits on the last digit
**And** if OTP is incorrect, an inline error appears below the field — the page does not reload
**And** the OTP delivery channel is email in this story; SMS (Twilio/SNS) is deferred to a later story

**Given** a coach attempts to register with an email already in use
**When** the registration form is submitted
**Then** the response indicates the email is already registered (without revealing whether the account is verified)
**And** a "Sign in instead" link is displayed

**Given** a coach's registration is complete (Basic verification)
**When** they are redirected
**Then** their profile is not publicly listed on the marketplace — it only goes live after profile builder and availability setup are completed (subsequent stories in Epic 2)

**Given** the registration form contains a free-text field (first name, last name)
**When** the coach types an email address or phone number pattern into the field
**Then** a real-time amber warning bar appears inside the field: "Contact details will be removed on save"
**And** on save, the value is passed through `infrastructure.sanitizer.ContactDetailSanitizer` server-side before persistence — redaction is silent and never a blocking error

*Dev notes: Extend `platform.security`. New entities: `email_verification_tokens` (token UUID, userId, expiresAt, used), `phone_otp_tokens` (otpHash SHA-256, userId, expiresAt, used). OTP stored hashed as SHA-256(otp+userId) in DB — no Redis in this phase. Email rendered via Thymeleaf (`coachEmailVerify.html`, `coachOtp.html`) with locale from `user.langKey`; delivered via `infrastructure.ses.SesEmailService` (SesV2Client). `@PreAuthorize` on all non-public endpoints. Test: `CoachRegistrationResourceIT`.*

### Story 1.4: Parent Registration, Player Profiles & Shadow Accounts

As a parent,
I want to register and create one or more player profiles under my account,
So that I can manage bookings, track development, and maintain oversight of my child's coaching sessions from a single login.

**Acceptance Criteria:**

**Given** a visitor navigates to the parent registration page
**When** they submit name, email, password, and phone number
**Then** a new `users` record is created with role `PARENT` and status `UNVERIFIED`
**And** email verification and phone OTP follow the same flow as Story 1.3
**And** Terms of Service, Privacy Policy, and Parent Consent Policy checkboxes are all required and must not be pre-checked
**And** the legal text for each policy must be scrollable in an inline container before its checkbox activates

**Given** a parent has completed verification
**When** they create a player profile (name, date of birth, position)
**Then** a `player_profiles` record is created linked to the parent via `parent_player_links` (parentId, playerId)
**And** the player's age tier is calculated from date of birth and stored: U10 / 10–12 / 13–17 / 18+
**And** a `ParentPlayerLink` record is created; the system rejects creation if a `parent_player_links` record already exists for that player (one-parent-per-player enforcement)

**Given** a parent attempts to add a second parent to a player profile that already has one
**When** the request reaches `POST /api/security/players/{playerId}/link-parent`
**Then** the response is `409 Conflict` with `ErrorDto` code `security.playerAlreadyHasParent`
**And** the second parent account is not linked

**Given** a parent is creating a player profile for a minor (under 18)
**When** they submit the date of birth
**Then** the Parent Consent Policy acceptance is recorded with a timestamp against the player profile
**And** the player's age tier is set and stored — it can only be changed by the parent (not the player) through an explicit consent escalation flow

**Given** a parent has two or more player profiles
**When** they are logged in and view the `ParentChildSwitcher` component in the app header
**Then** they see all their player profiles listed with avatar, name, and age tier
**And** tapping a profile switches the active player context and reloads the current page for that player
**And** the switcher is accessible at all times from the header — it is never hidden in settings

**Given** a parent's player profile has age tier U10
**When** the player profile is created
**Then** no independent player account is created — all actions are parent-managed only
**And** the platform stores this constraint in the age tier field read from ConfigService

*Dev notes: New entities: `player_profiles` (name, dateOfBirth, position, ageTier ENUM, parentId UUID), `parent_player_links` (parentId, playerId, consentAcceptedAt, consentPolicyVersion). Unique constraint `uq_parent_player_links_player_id` enforces one-parent-per-player at DB level AND application layer. `ShadowAccountService` manages linkage. `AgePolicyService` reads age brackets from ConfigService. Frontend: `ParentChildSwitcher.vue` in header layout. Test: `ShadowAccountServiceIT`, `ParentRegistrationResourceIT`.*

### Story 1.5: Authentication & JWT Security

As a user,
I want to securely log in and remain authenticated across sessions,
So that my account and any linked player data are protected against unauthorized access.

**Acceptance Criteria:**

**Given** a registered and verified user submits valid credentials to `POST /api/auth/login`
**When** authentication succeeds
**Then** the response sets two `HttpOnly; Secure` cookies: an access token (15-min TTL) and a refresh token (7-day TTL, `SameSite=Lax`, cookie name `rtkn`)
**And** a non-HttpOnly `skp` cookie (URL-encoded JSON `{"id":<Long>,"role":"<ROLE>"}`) is also set for frontend state hydration
**And** the response body contains `userId`, `role`, and `displayName` — never the raw token string
**And** the refresh token is persisted in the `refresh_tokens` table with `token_hash` (SHA-256 of raw token), `expires_at`, and `used: false`

**Given** a client presents a valid, unused refresh token cookie to `POST /api/auth/refresh`
**When** the token is found and not expired
**Then** the old token record is marked `used=true`, a new access token and refresh token pair is issued (rotation), and the `skp` cookie is refreshed
**And** if the refresh token has already been used (theft detected), all refresh tokens for that user are revoked (`used=true`), all auth cookies are cleared, and the response is `401 Unauthorized`
**And** if the token is expired or not found, all auth cookies are cleared and the response is `401 Unauthorized`

**Given** a user sends `POST /api/auth/logout`
**When** the request is received
**Then** the presented refresh token is marked `used=true` in the database
**And** all auth cookies (`rtkn`, `skp`, `potc`, `bcookie`, `user=`, `ION`, `rint`) are cleared via `Set-Cookie: ...; Max-Age=0`
**And** subsequent requests with the cleared cookies receive `401 Unauthorized`

**Given** an unauthenticated request reaches any protected endpoint
**When** no valid access token cookie is present
**Then** the response is `401 Unauthorized` with `ErrorDto`
**And** the frontend redirects to `/login?redirect=<originally-requested-path>`

**Given** any Skillars user (`skillarsRole != null`) with `verificationStatus != BASIC_VERIFIED` attempts to log in
**When** credentials are correct but verification is incomplete
**Then** the response is `403 Forbidden` with `ErrorDto` code `security.accountNotVerified`
**And** the user is NOT signed in — no cookies are set

**Given** login fails due to invalid credentials
**When** the user's email has accumulated 5 failed attempts within a 15-minute window
**Then** subsequent attempts return `429 Too Many Requests` with `ErrorDto`
**And** the attempt threshold and lock window are read from ConfigService keys `security.login.max-attempts` / `security.login.lock-window-minutes`
**And** attempts are tracked in the DB `login_attempts` table (multi-node safe — no in-memory cache)

**Given** a successfully authenticated user
**When** they are redirected after login
**Then** `COACH` → `/coach/command-center`; `PARENT` → `/parent/dashboard`; `PLAYER` → `/player/locker-room`; `ADMIN` → `/admin/health-dashboard`; no-role (legacy) users → `/dashboard`

**Given** the application is loaded or the page is reloaded
**When** a `skp` cookie is present (set by login or refresh)
**Then** `auth.store.js` (Pinia) hydrates `userId` and `role` from the URL-decoded JSON in `skp`
**And** the router navigation guard uses `authStore.isAuthenticated` (not `document.cookie.includes('user=')`) to decide access

*Dev notes: Extends `platform.security`. New entities: `refresh_tokens` (BIGINT TSID PK via `BaseEntity`, `user_id BIGINT FK`, `token_hash VARCHAR(64)` SHA-256 hex, `expires_at TIMESTAMPTZ`, `used BOOLEAN DEFAULT false`, `rotated_at TIMESTAMPTZ`) and `login_attempts` (BIGINT TSID PK, `identifier VARCHAR(255)`, `attempted_at TIMESTAMPTZ`). New endpoints are parallel to legacy `/authenticate` — nothing removed. `AuthResource`: `POST /api/auth/login`, `/refresh`, `/logout` — all in `PUBLIC_ENDPOINTS`. `AuthService` handles token logic; `AuthResource.login()` calls `loginTokenManager.ensureClientHasPreLoginId()` before delegating to service (fcookie requirement). SHA-256 via Java 17 `HexFormat`. ConfigService via `configService.find(key).map(Integer::parseInt).orElse(default)`. Test: `AuthResourceIT` (11 integration test cases, `@SpringBootTest + @Testcontainers`).*

### Story 1.6: Age-Tier Enforcement & Family Data Isolation

As a platform operator,
I want age-based restrictions and parent-scoped data isolation enforced uniformly across all service calls,
So that minor players are protected and no parent can ever access another family's data regardless of how the API is called.

**Acceptance Criteria:**

**Given** `AgePolicyService.getAgeTier(dateOfBirth)` is called
**When** the date of birth falls in any range
**Then** it returns the correct tier: `U10` (age < 10), `AGE_10_12` (10–11), `AGE_13_17` (13–17), `AGE_18_PLUS` (≥18)
**And** the tier boundaries are read from ConfigService keys (not hardcoded) so admin can adjust them without deployment
**And** the calculation is performed in the `@Service` layer only — never in a `@RestController`, `@Entity`, or `@Repository`

**Given** a parent makes any API request that involves a player profile
**When** the request reaches a `@Service` method
**Then** Spring Security `@PreAuthorize` verifies the authenticated user is the registered parent for that playerId before the method executes
**And** if the check fails, the response is `403 Forbidden` with `ErrorDto` — never `404` (no resource enumeration)

**Given** any repository method that queries player data
**When** the query is built
**Then** it includes a mandatory `parentId` parameter — no `findByPlayerId(UUID playerId)` method exists without a corresponding `parentId` filter
**And** a query that omits `parentId` fails to compile (enforced by method naming convention and code review AC)

**Given** a player belongs to age tier `U10`
**When** any service checks messaging permissions via `AgePolicyService`
**Then** messaging is prohibited — the service returns `false` for `canMessage(playerId)`, and no message endpoints are callable for that player
**And** the messaging tab is absent from the player's UI (not disabled — invisible; see UX-DR21)

**Given** a player belongs to age tier `AGE_10_12`
**When** messaging permissions are checked
**Then** direct player-to-coach messaging is prohibited; only parent-managed messaging is allowed
**And** all messages in this tier are flagged `parentVisible: true` automatically

**Given** a player belongs to tier `AGE_13_17`
**When** messaging permissions are checked
**Then** player may initiate messages to their coach, but all threads are marked `parentVisible: true` and accessible to the parent at all times
**And** the parent can see the full message history without any opt-in — visibility is automatic and permanent

**Given** a Scout-tier coach attempts to access Session Builder, Skills Radar, Performance Reports, or the Unified Timeline
**When** the feature gate is evaluated via `ConfigService`
**Then** the endpoint returns `403 Forbidden` with `ErrorDto` code `security.featureGated`
**And** in the UI, the restricted feature is shown as a soft teaser overlay with an upgrade CTA over a blurred preview (UX-DR22) — never a full-screen block

**Given** a parent on a free tier attempts to access a paid-tier-gated feature
**When** `AgePolicyService` or the feature gate check fires
**Then** behaviour matches the tier-gating pattern above — soft overlay, never a hard page block

*Dev notes: `AgePolicyService` in `platform.security` reads min age boundaries from ConfigService (`platform.age.u10.maxAge`, `platform.age.teen.maxAge` etc.). `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")` on all player-scoped service methods; `PlayerOwnershipGuard` is a Spring `@Component`. All player `@Repository` interfaces: compile-time enforcement via naming convention — every method name containing `Player` must also have a `parentId` parameter (enforced by Architecture Decision Record + code review checklist, not runtime). `AgePolicyService.getMessagingPolicy(playerId)` returns a `MessagingPolicy` record (canMessage, parentVisible, directAllowed). Test: `AgePolicyServiceTest` (unit), `FamilyDataIsolationIT` (integration — verifies cross-family 403s).*

### Epic 2: Coach Discovery & Onboarding
Guests can browse and discover coaches on the marketplace. Coaches can self-register, build public profiles, and go live after Basic verification. Trust signals (verification tier, capability badges, star rating) are visible and scannable on coach cards.
**FRs covered:** FR-MKT-001, FR-MKT-002, FR-MKT-003, FR-MKT-004, FR-MKT-005, FR-MKT-006, FR-MKT-007

### Epic 3: Booking & Scheduling
Parents can request, confirm, and manage sessions through the full 16-state booking lifecycle. Coaches manage availability and see their Command Center (weekly schedule, projected revenue). Session completion via Live Mode and Quick Complete is operational. Parents see their session pack status and can reschedule.
**FRs covered:** FR-BKG-001–013, FR-POR-001, FR-POR-002, FR-POR-003

### Epic 4: Session Builder & Drill Library
Coaches on Instructor+ can plan sessions using four-block structure, intelligent drill suggestions, Session DNA analysis, drag-and-drop management, equipment packing lists, and session templates. The Foundation 20 platform drill library is live. The 30-second pitch-side wrap-up workflow is operational. Players see their assigned homework drills in the Locker Room.
**FRs covered:** FR-SES-001–010, FR-DRL-001–006, FR-POR-004

### Epic 5: Player Development Intelligence
Coaches can track player development through automatic SLU accumulation and Skills Radar assessments. Parents and players see their development dashboard (cumulative SLU, neglected skill alerts, radar chart with baseline comparison). Coaches can generate and share PDF Performance Reports. A unified player timeline records all events across all coaches.
**FRs covered:** FR-DEV-001–009, FR-DEV-011–014, FR-POR-007, FR-POR-008, FR-POR-009

### Epic 6: Video Module
Coaches can upload drill demos and review videos. Players (18+) can upload homework practice clips. All uploads flow through the TUS pipeline with three-layer content moderation (Arachnid CSAM + VideoIntel explicit + minor safety gate). Storage and bandwidth quotas are enforced concurrency-safely. Parents approve videos for minor players. Playback is secured with signed URLs. The reactive VideoStatusCard updates live.
**FRs covered:** FR-VID-001–020, FR-POR-005, FR-POR-006, FR-POR-011, FR-POR-012, FR-POR-013, FR-TSC-004, FR-TSC-006 (video)

### Epic 7: Payments & Subscriptions
Parents can purchase session packs and player subscriptions via Stripe Connect. Coaches can accept payments, manage their subscription tier, set pricing (per-session and bundle), and view a transparent revenue dashboard. The refund and cancellation policy matrix is enforced. Reliability strikes are automatically issued on coach no-shows and late cancellations.
**FRs covered:** FR-PAY-001–017

### Epic 8: Messaging
Coaches, parents, and age-eligible players can communicate within the platform. Gemini moderation (fail-closed) runs on all messages involving minor players. Parents have full, permanent visibility into all coach-player messages for their minor players. Abuse reporting is available. Messages auto-purge after 24 months.
**FRs covered:** FR-MSG-001–004, FR-POR-010, FR-TSC-006 (messaging)

### Epic 9: Reviews & Ratings
Users with at least one completed paid session and no active dispute can leave star ratings and written reviews for coaches. Coaches can reply to reviews. Reviews and aggregate ratings surface on coach marketplace cards.
**FRs covered:** FR-REV-001, FR-REV-002, FR-REV-003

### Epic 10: Admin & Platform Safety
Platform operators can manage the full moderation queue (flagged videos, reported messages, reviews, AI-flagged content), resolve booking disputes using the session audit trail, apply tiered enforcement actions, handle appeals, oversee platform-wide financials, manage coach verification upgrades, and process GDPR data export and erasure requests.
**FRs covered:** FR-ADM-001–006, FR-TSC-001, FR-TSC-002, FR-TSC-003, FR-TSC-005, FR-TSC-006 (admin), FR-TSC-007 (admin tools)

---

## Epic 2: Coach Discovery & Onboarding

Guests can browse and discover coaches on the marketplace. Coaches can self-register, build public profiles, and go live after Basic verification. Trust signals (verification tier, capability badges, star rating) are visible and scannable on coach cards.

### Story 2.1: Coach Profile Builder

As a newly verified coach,
I want to complete a guided profile builder so my listing goes live on the marketplace,
So that parents and players can find and book me without requiring admin approval.

**Acceptance Criteria:**

**Given** a coach has completed Basic verification (status `BASIC_VERIFIED`, Story 1.3)
**When** they log in for the first time
**Then** they are redirected to Step 1 of the 5-step profile builder — the Command Center is not accessible until all 5 steps are complete
**And** a progress indicator shows the current step and total steps throughout the flow

**Given** the coach is on Step 1 (Identity & Location)
**When** they submit display name, bio, city, district, and coaching languages
**Then** the values are saved to `coach_profiles` (coachId, displayName, bio, city, district, languages[], canonicalTimezone, status: DRAFT)
**And** `ContactDetailSanitizer` runs server-side on `bio` before persistence — any detected email or phone number is redacted
**And** `canonicalTimezone` is auto-detected from the browser locale and stored — it cannot be left blank

**Given** the coach is on Step 2 (Specialties & Age Groups)
**When** they select one or more skill specializations and age groups they coach
**Then** the selections are saved to `coach_specialties` and `coach_age_groups` tables linked to the coach profile
**And** at least one specialization and one age group must be selected before advancing

**Given** the coach is on Step 3 (Pricing)
**When** they configure per-session price and optionally one or more session pack bundles (e.g., 5 sessions, 10 sessions at a discount)
**Then** prices are saved to `coach_pricing` (coachId, perSessionPrice, currency: EUR) and `session_packs` (coachId, sessionCount, totalPrice, label)
**And** the per-session price is required; session packs are optional
**And** currency is locked to EUR and not user-configurable

**Given** the coach is on Step 4 (Availability)
**When** they define one or more recurring weekly availability windows (day of week, start time, end time, timezone)
**Then** the windows are saved to `coach_availability_windows` (id UUID, coachId, dayOfWeek SMALLINT, startTime TIME, endTime TIME, canonicalTimezone VARCHAR)
**And** at least one availability window must be saved before advancing
**And** times are stored in UTC derived from the coach's `canonicalTimezone`

**Given** the coach is on Step 5 (Profile Photo)
**When** they upload a profile photo (JPEG/PNG, max 5MB)
**Then** the image is stored via the `filestorage` module and the URL saved to `coach_profiles.photoUrl`
**And** the profile photo is optional — a "Skip for now" option advances to completion without a photo

**Given** the coach completes Step 5
**When** they submit the final step
**Then** `coach_profiles.status` transitions from `DRAFT` to `ACTIVE`
**And** the coach's subscription tier is set to `SCOUT` (free default) in `coach_subscriptions`
**And** the profile is immediately visible on the marketplace
**And** the coach is redirected to the Command Center

**Given** a coach exits the builder mid-flow and returns later
**When** they log in again
**Then** they resume from the last incomplete step — completed step data is not lost
**And** partial `DRAFT` profiles are never indexed or visible on the marketplace

**Given** a coach re-edits their bio after going live
**When** they save the updated bio
**Then** `ContactDetailSanitizer` runs server-side again before persistence
**And** a real-time amber warning bar appears in the frontend text field while typing if contact details are detected (UX-DR24)

*Dev notes: New module `platform.marketplace`. New entities: `coach_profiles` (coachId UUID PK, displayName, bio, city, district, languages VARCHAR[], canonicalTimezone, photoUrl, status ENUM DRAFT/ACTIVE, createdAt TIMESTAMPTZ), `coach_specialties` (coachId, skill VARCHAR), `coach_age_groups` (coachId, ageTier ENUM), `coach_pricing` (coachId, perSessionPrice NUMERIC, currency VARCHAR DEFAULT 'EUR'), `session_packs` (id UUID, coachId, sessionCount INT, totalPrice NUMERIC, label VARCHAR), `coach_availability_windows` (id UUID, coachId, dayOfWeek SMALLINT, startTime TIME, endTime TIME, canonicalTimezone VARCHAR), `coach_subscriptions` (coachId, tier ENUM SCOUT/INSTRUCTOR/ACADEMY, activeSince TIMESTAMPTZ). `ProfileBuilderResource`: PUT /api/marketplace/coaches/me/profile/steps/{stepNumber}. `CoachProfileService.publishProfile()` performs DRAFT→ACTIVE transition. `ContactDetailSanitizer` invoked in service layer before any free-text persistence. Test: `CoachProfileBuilderIT`.*

### Story 2.2: Coach Marketplace & Search

As a guest or registered user,
I want to browse and filter coaches on the marketplace,
So that I can find a coach that matches my child's needs before committing to a booking.

**Acceptance Criteria:**

**Given** any visitor (authenticated or guest) navigates to the marketplace
**When** the page loads
**Then** the page renders in a "search prompt" state — no coach cards are shown until a city is entered
**And** once a city is submitted, a grid of `CoachCard` components is displayed — 3 columns on desktop (≥1200px), 2 on tablet (768–1199px), 1 on mobile
**And** skeleton cards matching the exact dimensions of real `CoachCard` components are shown while city results are loading (UX-DR26)
**And** only coaches with `coach_profiles.status = ACTIVE` are returned — DRAFT profiles are never included

**Given** city results are displayed
**When** the default sort is applied
**Then** results are sorted alphabetically by display name within the searched city
**And** re-sorting by price or star rating is available via a sort control without a full page reload

**Given** a user enters a city and submits the search
**When** `GET /api/marketplace/coaches?city={city}&page=0&size=20` is called
**Then** only ACTIVE coaches in that city are returned, paginated (20 per page; `hasNext` signals further pages)
**And** the user can narrow results by applying additional filters — district, language, price range, age group, skill specialization — these are exposed only after city entry and re-trigger the query
**And** a star rating filter (`minRating`) is available; at this stage all ratings default to 0.0 (Epic 9 populates reviews), so `minRating > 0` returns no results
**And** the active filter state (including city) is persisted in URL query params — navigating to a profile and returning via the browser back button restores the full search

**Given** a `CoachCard` is rendered
**When** displayed in the grid
**Then** it shows: profile photo (or avatar placeholder), display name, verification tier badge (Basic/Trusted/Featured), star rating + review count, city/district, top 2 specialties, per-session price, capability badges for active premium tools, `ReliabilityIndicator` component
**And** trust signals (verification badge, reliability score, capability badges) appear above the price line — all visible without scrolling the card (UX-DR7)
**And** the `ReliabilityIndicator` always shows a label — "No reliability issues" in green for zero strikes, "X issues (90 days)" in amber for 1–2, "Review reliability score" in red for 3+ (UX-DR8)

**Given** a guest views the marketplace
**When** they attempt to contact or book a coach
**Then** they are redirected to the registration/login page with a return URL to the coach profile
**And** the browse and view experience is fully available without registration (FR-MKT-005)

**Given** the marketplace returns no results for the applied filters
**When** the empty state renders
**Then** an icon, headline ("No coaches found"), and a "Clear filters" CTA are shown — never a bare empty list (UX-DR25)

*Dev notes: `platform.marketplace`. `CoachMarketplaceResource`: GET /api/marketplace/coaches (required: `city` — returns 400 if omitted; optional: `district`, `language`, `minPrice`, `maxPrice`, `ageGroup`, `skill`, `minRating`, `sortBy`, `page` (default 0), `size` (default 20)). `CoachSearchService` queries `coach_profiles` via `JpaSpecificationExecutor` + `Specification<CoachProfile>` — only the current page's rows are loaded. Batch-enriches specialties, pricing, and reliability strikes using page-scoped IDs. Aggregate rating computed from `reviews` table (Epic 9 populates; default 0.0 / 0 reviews at this stage). Reliability strike count read from `coach_reliability_strikes` table (Epic 7 populates; default 0). `CoachCard.vue` component with `ReliabilityIndicator.vue` sub-component. Frontend filter state preserved in URL query params (Pinia store + Vue Router sync). Test: `CoachMarketplaceResourceIT`.*

### Story 2.3: Coach Public Profile Page

As a guest or registered user,
I want to view a coach's full public profile,
So that I can assess their suitability before making a booking decision.

**Acceptance Criteria:**

**Given** a visitor clicks a coach card or navigates directly to `/coaches/{coachId}`
**When** the profile page loads
**Then** the following sections are displayed: profile photo, display name, verification tier badge, capability badges, aggregate star rating + review count, bio, languages, city/district, specialties, age groups coached, per-session price and available session packs, current availability status ("Available" / "Unavailable"), and a media gallery
**And** the page is accessible to guests — no login required to view (FR-MKT-005)

**Given** a verification tier badge is displayed
**When** the badge renders
**Then** it reflects the coach's current tier: Basic (email + phone verified), Trusted (admin-granted), or Featured (admin-granted)
**And** hovering or tapping the badge shows a tooltip explaining what each tier means
**And** the badge is granted by admin action only — coaches cannot self-upgrade beyond Basic (FR-MKT-003)

**Given** capability badges are displayed
**When** the badge set renders
**Then** only badges for tools the coach actively uses are shown: Video Feedback, Performance Reports, Homework, Skills Radar, Verified Identity
**And** a badge is absent if the coach's current subscription tier does not include the tool or the tool has not been used in the last 90 days (configurable via ConfigService)
**And** hovering or tapping a badge shows a one-line description of what that capability means for the player

**Given** the media gallery section is rendered
**When** the coach has uploaded gallery media
**Then** up to 6 images or short clips are shown in a scrollable horizontal strip
**And** tapping any item opens a full-screen lightbox overlay
**And** if no media has been uploaded, the gallery section is hidden entirely — no empty placeholder visible to visitors

**Given** a guest views the profile
**When** they tap the "Book a session" or "Contact" button
**Then** they are redirected to the registration/login page with a return URL back to this profile
**And** the button label for guests reads "Sign up to book" — not "Book"

**Given** an authenticated parent views the profile
**When** they tap "Book a session"
**Then** they are taken to the booking flow (Epic 3) pre-populated with this coach
**And** the `SessionPackTracker` component showing their remaining credits for this coach is visible before the booking CTA (UX-DR12, FR-BKG-001)

**Given** the `ReliabilityIndicator` is rendered on the profile page
**When** displayed
**Then** it follows the same three-state label rule as on the marketplace card (UX-DR8) — never silent on zero issues

*Dev notes: `platform.marketplace`. `CoachProfileResource`: GET /api/marketplace/coaches/{coachId}. `CoachProfileService.getPublicProfile(coachId)` returns `CoachProfileDto` (Java record). Media gallery items stored in `coach_media` (id UUID, coachId, fileUrl, mediaType ENUM IMAGE/VIDEO, displayOrder INT, uploadedAt TIMESTAMPTZ) — populated via `filestorage` module. Capability badge eligibility logic in `CoachCapabilityService`: reads coach subscription tier from `coach_subscriptions`, checks last-used timestamps from relevant module tables (drill uploads, radar entries, report generations); badge threshold period read from ConfigService. `SessionPackTracker.vue` component renders from `booking.store.js` credits data (stub at this stage; wired in Epic 3). Test: `CoachProfileResourceIT`.*

### Story 2.4: Contact Detail Sanitization UX

As a platform operator,
I want email addresses and phone numbers automatically detected and removed from all coach free-text inputs,
So that coaches cannot use profile fields to solicit off-platform contact and circumvent the booking system.

**Acceptance Criteria:**

**Given** a coach is typing in any free-text field (bio, about, coaching philosophy, or any future text field)
**When** the field value matches an email address or phone number pattern
**Then** a non-blocking amber warning bar appears at the top of that field: "Contact details will be removed on save" (UX-DR24)
**And** the warning is driven by a debounced frontend check against `POST /api/util/sanitize-preview` — it is UX feedback only and does not block form submission
**And** the warning dismisses automatically if the coach removes the detected contact detail

**Given** a coach submits a form containing a free-text field
**When** the payload reaches any `@Service` method that persists free-text
**Then** `infrastructure.sanitizer.ContactDetailSanitizer` runs server-side on the value before any database write
**And** detected email addresses and phone numbers are replaced with a redaction notice (e.g., "[contact details removed]")
**And** the sanitized value — not the original — is persisted
**And** the coach sees the sanitized value reflected in their profile after save (no surprise on next load)

**Given** `POST /api/util/sanitize-preview` is called with a text payload
**When** the endpoint processes the request
**Then** it returns a preview of what the sanitized output would look like — it does not persist anything
**And** the endpoint is accessible to authenticated coaches only (`@PreAuthorize`)
**And** the response is a JSON object: `{ "original": "...", "sanitized": "...", "detectionFound": true/false }`

**Given** a coach attempts to submit a contact detail in a field where real-time detection was not shown (e.g., pasted quickly before the debounce fires)
**When** the server-side sanitizer runs
**Then** the value is still sanitized before persistence — the frontend warning is advisory, the server-side gate is mandatory
**And** no `400 Bad Request` is returned for contact detail detection — the save succeeds with the sanitized value

**Given** the `ContactDetailSanitizer` runs on any input
**When** no contact details are detected
**Then** the original value passes through unchanged — no performance overhead on clean inputs

*Dev notes: `infrastructure.sanitizer.ContactDetailSanitizer` is the server-side security gate — invoked in `platform.marketplace` service layer (and any other module persisting coach free-text). Regex patterns cover international phone formats and standard email patterns. `SanitizePreviewResource`: POST /api/util/sanitize-preview — thin wrapper, calls `ContactDetailSanitizer.preview(text)` and returns `SanitizePreviewDto` (Java record). Frontend debounce: 400ms after last keystroke. Test: `ContactDetailSanitizerTest` (unit — regex coverage), `SanitizePreviewResourceIT`.*

---

## Epic 3: Booking & Scheduling

Parents can request, confirm, and manage sessions through the full 16-state booking lifecycle. Coaches manage availability and see their Command Center (weekly schedule, projected revenue). Session completion via Live Mode and Quick Complete is operational. Parents see their session pack status and can reschedule.

### Story 3.1: Coach Availability Management

As a coach,
I want to manage my recurring availability windows and block out personal time,
So that parents can only request sessions when I am genuinely available.

**Acceptance Criteria:**

**Given** a coach navigates to their availability settings
**When** the calendar view loads
**Then** recurring weekly availability windows (set during onboarding, Story 2.1) are displayed as green slots on a 7-day week grid
**And** manual blocks are displayed as grey "unavailable" overlays
**And** the calendar renders all times in the coach's `canonicalTimezone` — never UTC-raw

**Given** a coach adds a new recurring availability window
**When** they select a day of week, start time, and end time and save
**Then** a new `coach_availability_windows` record is created (coachId, dayOfWeek, startTime, endTime, canonicalTimezone)
**And** times are stored as UTC derived from the coach's `canonicalTimezone`
**And** the new slot appears immediately in the calendar without a page reload

**Given** a coach edits an existing recurring availability window
**When** they update the day or times and save
**Then** the existing `coach_availability_windows` record is updated
**And** any already-confirmed bookings within the old window are not affected — editing availability is prospective only
**And** a warning is shown if the change would overlap with an existing confirmed booking: "You have a booking during this time. Changes apply to future availability only."

**Given** a coach deletes a recurring availability window
**When** they confirm the deletion
**Then** the `coach_availability_windows` record is removed
**And** existing confirmed bookings within that window are not cancelled — deletion is prospective only
**And** the slot is removed from the calendar immediately

**Given** a coach wants to block personal time (holiday, one-off unavailability)
**When** they select a specific date range and time range and save a manual block
**Then** a `coach_availability_blocks` record is created (id UUID, coachId, startDateTime TIMESTAMPTZ, endDateTime TIMESTAMPTZ, reason VARCHAR nullable)
**And** the blocked period is shown on the calendar as a grey overlay over any recurring availability that falls within it
**And** parents cannot request bookings during a blocked period — the slot is not offered in the booking flow

**Given** a coach views a week that contains both availability windows and manual blocks
**When** they view a day with partial overlap (e.g., a block covering 10:00–12:00 within a 09:00–13:00 window)
**Then** the calendar correctly shows the unblocked portions (09:00–10:00 and 12:00–13:00) as available
**And** the blocked portion (10:00–12:00) as unavailable

**Given** a parent views a coach's booking page (Epic 3.3)
**When** available slots are fetched
**Then** only time slots that fall within an active `coach_availability_windows` entry AND are not covered by a `coach_availability_blocks` entry are returned as bookable
**And** the slot availability query is read-only from this module — booking creation is handled in Story 3.3

*Dev notes: Initialize `platform.booking` module (package structure, `BookingConfiguration`, SecurityConstants entries, first Flyway migration resolving V-number). New entity: `coach_availability_blocks` (id UUID, coachId UUID, startDateTime TIMESTAMPTZ, endDateTime TIMESTAMPTZ, reason VARCHAR). `coach_availability_windows` created in Story 2.1 (`platform.marketplace`); `platform.booking` reads it directly (same DB, no cross-module API call needed at monolith stage). `AvailabilityResource`: GET /api/booking/coaches/{coachId}/availability (returns merged windows minus blocks for a given date range), PUT /api/booking/coaches/me/availability/windows, DELETE /api/booking/coaches/me/availability/windows/{id}, POST /api/booking/coaches/me/availability/blocks, DELETE /api/booking/coaches/me/availability/blocks/{id}. All endpoints `@PreAuthorize` — coaches manage only their own availability. Frontend: weekly calendar grid component using CSS Grid; no third-party calendar library. Test: `AvailabilityServiceTest` (unit — overlap logic), `AvailabilityResourceIT`.*

### Story 3.2: Session Pack Purchase & Credit Dashboard

As a parent,
I want to purchase a session pack for my child and always see how many credits remain,
So that I know what capacity we have before requesting a booking.

**Acceptance Criteria:**

**Given** a parent views a coach's profile or booking page
**When** the `SessionPackTracker` component renders
**Then** it displays credits used vs. total for the active pack with this coach, in one of four visual states: Healthy (>30% remaining, neutral), Warning (<30%, amber), Critical (1–2 credits left, amber + "Buy more sessions" CTA), Exhausted (0 credits, red + "Buy sessions" CTA) (UX-DR12)
**And** if no pack exists yet with this coach, the Exhausted state is shown with a "Buy sessions" CTA
**And** the tracker is always visible before any booking action — it cannot be scrolled past without seeing it

**Given** a parent taps "Buy sessions" on a coach's profile
**When** the purchase flow opens
**Then** they see all available session pack options for that coach (sessionCount, totalPrice, per-session equivalent price) plus the per-session single booking option
**And** each pack option displays its validity window ("Valid for X months — expires approximately [calculated date] if purchased today") so the parent can make an informed commitment before paying
**And** a `SessionPackTracker` showing current credits (if any) is visible at the top of the purchase screen

**Given** a parent selects a session pack and confirms purchase
**When** payment is captured (Stripe integration wired in Epic 7; at this stage, simulate capture with a stub)
**Then** a `session_packs_purchased` record is created (id UUID, parentId, playerId, coachId, sessionCount, creditsRemaining INT, purchasedAt TIMESTAMPTZ, expires_at TIMESTAMPTZ, status ENUM ACTIVE/EXHAUSTED/EXPIRED)
**And** `creditsRemaining` is set to `sessionCount`
**And** `expires_at` is set based on pack size: 1 session → 90 days, 2–5 sessions → 180 days, 6–10 sessions → 365 days, 11+ sessions → 548 days (all from `purchasedAt`; thresholds read from `platform_config`, admin-modifiable)
**And** the purchase confirmation displays "Credits valid until [expires_at date]"
**And** the `SessionPackTracker` updates immediately to reflect the new credit balance

**Given** a parent attempts to request a booking when `creditsRemaining = 0` for all packs with that coach
**When** the booking request is submitted
**Then** the request is rejected with `ErrorDto` code `booking.creditsExhausted`
**And** the `SessionPackTracker` is shown in Exhausted state with a "Buy sessions" CTA — the booking form is not submitted (FR-BKG-001)

**Given** a parent has multiple active session packs with the same coach
**When** a session is completed and a credit is deducted
**Then** credits are consumed from the oldest active pack first (FIFO)
**And** when a pack reaches `creditsRemaining = 0`, its status transitions to `EXHAUSTED`
**And** a `SessionPackExhaustedEvent` is published (via Spring ApplicationEvent) for downstream listeners (FR-PAY-009)

**Given** a parent views the Player Portal
**When** they navigate to the session pack dashboard
**Then** all active and exhausted packs are listed with coach name, original credit count, credits remaining, purchase date, expiry date, and pack status
**And** active packs expiring within 30 days display an amber "Expires in X days" warning with a "Pause" CTA
**And** paused packs show "Paused until [date]" with the revised expiry date
**And** expired packs show "Expired on [date]" in error state
**And** an empty state ("No session packs yet — find a coach to get started") with a marketplace CTA is shown if no packs exist (UX-DR25)

*Dev notes: `platform.booking`. New entities: `session_packs_purchased` (id UUID, parentId UUID, playerId UUID, coachId UUID, sessionCount INT, creditsRemaining INT, purchasedAt TIMESTAMPTZ, expires_at TIMESTAMPTZ NOT NULL, paused_until TIMESTAMPTZ nullable, status ENUM ACTIVE/EXHAUSTED/EXPIRED). `expires_at` is computed at purchase time from session count tier (thresholds in `platform_config`). `SessionPackService.deductCredit(playerId, coachId)` — FIFO across active packs, `@Transactional`, SELECT FOR UPDATE on the pack row (NFR-004). `SessionPackExhaustedEvent` published via `ApplicationEventPublisher` when `creditsRemaining` reaches 0. Payment capture stubbed via `PaymentGateway` interface (wired in Epic 7). `SessionPackResource`: GET /api/booking/players/{playerId}/packs, POST /api/booking/players/{playerId}/packs/purchase. `@PreAuthorize` parent ownership on all endpoints. `SessionPackTracker.vue` reads from `booking.store.js`. Expiry enforcement, pause logic, and scheduler defined in Story 3.9. Test: `SessionPackServiceTest` (unit — FIFO deduction, concurrent deduction with SELECT FOR UPDATE), `SessionPackResourceIT`.*

### Story 3.3: Booking Request & Approval Workflow

As a parent,
I want to request a session with a coach and receive confirmation once they accept,
So that sessions are scheduled with mutual agreement, backed by pre-purchased session credits.

**Acceptance Criteria:**

**Given** a parent has at least one effective available credit with a coach (effective = creditsRemaining from active, non-expired, non-paused packs minus credits already committed to in-flight bookings in REQUESTED, ACCEPTED, CONFIRMED, or UPCOMING status with that same coach)
**When** they select an available slot from the coach's calendar and submit a booking request
**Then** the backend validates: (a) `requestedStartTime` is in the future; (b) the coach profile status is `ACTIVE`; (c) the requested slot falls within the coach's configured availability windows (from Story 3.1); (d) effective credits > 0 for that player+coach pair
**And** a `bookings` record is created with status `REQUESTED` (id UUID, parentId BIGINT, playerId BIGINT, coachId UUID, requestedStartTime TIMESTAMPTZ, requestedEndTime TIMESTAMPTZ, status VARCHAR, canonicalTimezone VARCHAR, notes VARCHAR nullable, version INT for `@Version` optimistic locking, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ)
**And** the coach receives a notification email of the new request including the parent's notes
**And** the parent sees the booking in `REQUESTED` state with a `BookingStateChip` labelled "Awaiting coach response" in `--accent-warning`
**And** credits are coach-specific — a credit purchased with Coach A cannot satisfy a booking with Coach B
**And** credits are price-locked at purchase time — if the coach later changes their pricing, existing purchased credits remain valid at the original rate without re-authorisation

**Given** a booking is in `REQUESTED` state
**When** the coach accepts it
**Then** `BookingService.acceptBooking(bookingId, coachUserId)` is the only permitted method to advance the state — no direct `setStatus()` call outside of `BookingService`
**And** status transitions directly `REQUESTED → ACCEPTED → CONFIRMED` in a single transaction (no intermediate payment step — payment was pre-captured at session pack purchase time; Epic 7 will introduce Stripe pre-authorisation for future payment flows)
**And** the parent receives a notification: "Your session with [Coach] on [date] is confirmed"
**And** the `BookingStateChip` updates to "Confirmed" in `--accent-primary`

**Given** a booking is in `REQUESTED` state
**When** the coach declines it
**Then** `BookingService.transition(bookingId, DECLINE, context)` advances status to `DECLINED`
**And** no additional payment is captured (payment was pre-captured at pack purchase time)
**And** the parent is notified with the decline and a prompt to choose another slot
**And** no credit is deducted — credits are only deducted at `COMPLETED`; the declined booking is excluded from the in-flight count, freeing the soft-reserved credit for a new request

**Given** a coach does not respond to a `REQUESTED` booking
**When** the request has been open for longer than the configurable expiry window (read from ConfigService key `booking.request_expiry_hours`, default 48h)
**Then** the booking status is set to `DECLINED` via a scheduled job and a `BookingExpiredEvent` is published (distinct from `BookingDeclinedEvent` — auto-expiry is not an active coach rejection)
**And** the parent receives a dedicated expiry notification: "Your session request was not responded to in time" (not the coach-declined wording)
**And** the soft-reserved credit is freed automatically (expired booking excluded from in-flight count)

**Given** a booking reaches `CONFIRMED` status
**When** the session start time is within 24 hours (or is already in the past due to scheduler downtime — catch-up)
**Then** status automatically transitions to `UPCOMING` via a scheduled job; the catch-up query also picks up CONFIRMED bookings whose `requestedStartTime` has already passed
**And** reminder notifications are sent to both coach and parent separately for the 24h window (`PRIMARY`) and the 2h window (`SECONDARY`); each send is idempotent (stamped `primaryReminderSentAt` / `secondaryReminderSentAt`)

**Given** a parent views their upcoming sessions
**When** the bookings list renders
**Then** all sessions for all their player profiles are shown with: coach name, date/time in `canonicalTimezone`, status chip, player name, and `effectiveCreditsRemaining` for that coach (returned directly in `BookingResponse` — no separate pack load required)
**And** sessions are sorted chronologically — nearest first

**Given** a `BookingStateChip` renders any booking status
**When** displayed
**Then** the chip shows a plain-English label mapped from the internal status string — never the raw value (REQUESTED → "Awaiting coach response", ACCEPTED → "Accepted", CONFIRMED → "Confirmed", UPCOMING → "Upcoming", DECLINED → "Declined", COMPLETED → "Completed" [Story 3.6], CANCELLED → "Cancelled" [Story 3.10], DISPUTED → "Disputed" [Story 3.6]) (UX-DR11)
**And** each state maps to its designated CSS token colour; COMPLETED/CANCELLED/DISPUTED use neutral placeholder tokens until their respective stories define the final UX

**Given** a coach is authenticated
**When** they access their booking inbox
**Then** `GET /api/bookings/requests/coach` returns all `REQUESTED` bookings for that coach, sorted by `requestedStartTime ASC`
**And** each row shows: parent name, player name, requested date/time in `canonicalTimezone`, notes
**And** the coach can accept or decline each request from `CoachBookingRequestsPage.vue`

*Dev notes: `platform.booking`. New entity: `bookings` (id UUID, parentId BIGINT, playerId BIGINT, coachId UUID, requestedStartTime TIMESTAMPTZ, requestedEndTime TIMESTAMPTZ, status VARCHAR(30), canonicalTimezone VARCHAR, notes VARCHAR(500) nullable, version INT for @Version, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ). `BookingService.acceptBooking(bookingId, coachUserId)` and `BookingService.declineBooking(bookingId, coachUserId)` are the permitted transition methods. `BookingResource`: POST /api/bookings/requests, GET /api/bookings/requests (parent), PUT /api/bookings/requests/{id}/accept, PUT /api/bookings/requests/{id}/decline, GET /api/bookings/requests/coach. `BookingResponse` includes `effectiveCreditsRemaining` (computed at query time from `sumActiveCredits - countInFlightBookings` for that player+coach pair). Effective credit check via `SessionPackService.hasCredits(playerId, coachId)` — uses `repository.sumActiveCredits()` minus `bookingRepository.countInFlightBookings()` to prevent TOCTOU double-booking; uses `SELECT FOR UPDATE` on `session_packs_purchased` rows when effective credits ≤ 1 to prevent concurrent double-booking. Slot validation in `createBookingRequest`: coach must be ACTIVE, `requestedStartTime` must be in the future, slot must fall within a `coach_availability_window` (Story 3.1). Auto-expiry fires `BookingExpiredEvent` (distinct from `BookingDeclinedEvent`). Reminder scheduler includes catch-up query for CONFIRMED bookings whose `requestedStartTime` is already past. V31 schema adds index `(player_id, coach_id, status)` and forward-declares all terminal statuses including CANCELLED. `@TransactionalEventListener(AFTER_COMMIT)` in `platform.notification` for all notifications. Test: `BookingServiceTest` (unit — state machine, soft-reservation, slot validation, coach ACTIVE check), `BookingRequestResourceIT`.*

### Story 3.4: Booking State Machine & SSE

As a coach or parent,
I want booking status changes to appear in real time without reloading the page,
So that I can react immediately to confirmations, cancellations, and disputes as they happen.

**Acceptance Criteria:**

**Given** the full 16-state booking state machine is implemented
**When** any transition is attempted
**Then** only `BookingService.transition(bookingId, BookingEvent, context)` may change the booking status — all 16 states and their valid transitions are encoded in the state machine
**And** an attempt to apply an invalid transition (e.g., COMPLETED → REQUESTED) throws `IllegalStateTransitionException` and returns `409 Conflict` with `ErrorDto` code `booking.invalidTransition`
**And** the valid transition graph is: REQUESTED → ACCEPTED | DECLINED; ACCEPTED → PAYMENT_PENDING | CANCELLED_COACH | CANCELLED_PARENT; PAYMENT_PENDING → CONFIRMED | REFUND_PENDING; CONFIRMED → UPCOMING | CANCELLED_COACH | CANCELLED_PARENT; UPCOMING → IN_PROGRESS | NO_SHOW_PLAYER | NO_SHOW_COACH | CANCELLED_COACH | CANCELLED_PARENT; IN_PROGRESS → COMPLETED_PENDING_CONFIRMATION | DISPUTED; COMPLETED_PENDING_CONFIRMATION → COMPLETED | DISPUTED; COMPLETED → DISPUTED; DISPUTED → REFUND_PENDING | COMPLETED; REFUND_PENDING → REFUNDED

**Given** a booking status changes on the server
**When** the transition completes and the `@TransactionalEventListener(AFTER_COMMIT)` fires a `BookingStatusChangedEvent`
**Then** the SSE emitter for every connected client subscribed to that booking pushes the new status immediately
**And** the `BookingStateChip` on the parent and coach UIs updates without a page reload

**Given** a client connects to `GET /api/booking/bookings/{id}/events`
**When** the SSE connection is established
**Then** the server registers a `SseEmitter` for that booking and client
**And** the emitter has a 5-minute timeout; on timeout the client receives a `heartbeat` event to trigger reconnection
**And** on reconnection the client receives the current booking status immediately as the first event

**Given** the SSE connection drops (network interruption)
**When** the frontend detects the connection loss
**Then** it reconnects with exponential backoff: 1s → 2s → 4s → max 30s
**And** after 3 consecutive failed reconnects it falls back to 2-second polling on `GET /api/booking/bookings/{id}`
**And** once SSE reconnects successfully, polling stops

**Given** a cancellation is initiated
**When** a parent cancels a `CONFIRMED` or `UPCOMING` booking
**Then** `BookingService.transition(bookingId, CANCEL_PARENT, context)` advances status to `CANCELLED_PARENT`
**And** the refund eligibility is calculated according to the cancellation matrix (FR-PAY-012): >24h before session → full refund eligible; 6–24h → 50%; <6h → no refund
**And** the refund amount is stored on the booking record for Epic 7 payment processing

**Given** a coach cancels a `CONFIRMED` or `UPCOMING` booking
**When** `BookingService.transition(bookingId, CANCEL_COACH, context)` is called
**Then** status advances to `CANCELLED_COACH`
**And** full refund is flagged as eligible regardless of timing
**And** if the cancellation is within 24 hours of the session, a reliability strike event is queued (processed in Epic 7)

**Given** a no-show occurs
**When** the session start time passes with no `START_SESSION` event received within a 15-minute grace period
**Then** the booking is eligible for `NO_SHOW_PLAYER` or `NO_SHOW_COACH` transition — admin or system can apply the appropriate event
**And** `NO_SHOW_PLAYER` → credit deducted; `NO_SHOW_COACH` → full refund eligible + reliability strike queued

*Dev notes: `platform.booking`. Add `COMPLETED_PENDING_CONFIRMATION` to the `BookingStatus` enum and `QUICK_COMPLETE` to `BookingEvent`. `BookingStateMachine` encodes all valid transitions as a `Map<BookingStatus, Set<BookingEvent>>` — consulted by `BookingService.transition()` before any state change. `BookingStatusChangedEvent` published AFTER_COMMIT via `ApplicationEventPublisher`. `BookingSseService` maintains a `ConcurrentHashMap<UUID, List<SseEmitter>>` keyed by bookingId; cleaned up on emitter completion/timeout. `BookingEventResource`: GET /api/booking/bookings/{id}/events (SSE endpoint). Frontend: `useBookingSse(bookingId)` composable in `booking.store.js` — handles backoff, polling fallback, and cleanup on component unmount. Refund amount stored in `bookings.refundAmount NUMERIC` and `bookings.refundEligibility ENUM FULL/PARTIAL/NONE`. Test: `BookingStateMachineTest` (unit — all valid and invalid transitions), `BookingSseIT` (integration).*

### Story 3.5: Scheduling Views & Timezone Management

As a coach or parent,
I want to see my schedule displayed in the correct timezone with projected revenue and session pack status always visible,
So that I can manage my time and finances without mental timezone arithmetic.

**Acceptance Criteria:**

**Given** a coach navigates to the Command Center
**When** the schedule view loads
**Then** all sessions are rendered in the coach's `canonicalTimezone` by default
**And** the layout is 3 columns on desktop: sidebar (active client roster + quick actions), schedule (week view with session blocks), revenue panel (projected weekly revenue from CONFIRMED + UPCOMING sessions)
**And** on mobile, the three columns collapse to a single content stream with activity-first ordering (UX-DR5)

**Given** the Command Center revenue panel renders
**When** sessions in `CONFIRMED` or `UPCOMING` status exist for the current week
**Then** total projected gross revenue is displayed (sum of session prices for those bookings)
**And** the 8% platform commission is shown as a deduction line so the coach sees net projected payout
**And** commission rate is read from ConfigService — never hardcoded

**Given** the Command Center schedule view renders
**When** the coach's week is displayed
**Then** schedule gaps (available windows with no booking) are visually distinct from booked sessions and blocked times
**And** tapping a gap offers a one-tap "Share this slot" action that generates a shareable booking link for that specific time

**Given** a parent views their upcoming sessions list
**When** the list renders
**Then** all session times display in the Pitch Timezone (the coach's `canonicalTimezone`) by default (FR-BKG-004)
**And** a toggle "Show in my time" switches all displayed times to the browser's local timezone for that session
**And** the toggle state is per-session and does not persist across page reloads

**Given** a user logs in and their browser timezone differs from the Pitch Timezone of their most recent booking
**When** login completes and the home screen loads
**Then** a non-blocking info bar appears: "Your browser timezone ([zone]) differs from the session timezone ([pitch zone]). Times are shown in session timezone." (UX-DR23)
**And** the bar auto-dismisses after 8 seconds
**And** it does not appear again in the same session after dismissal

**Given** a parent views the Player Portal sessions screen
**When** the upcoming sessions section renders
**Then** they see only sessions for the currently active player profile (respecting `ParentChildSwitcher` selection)
**And** session pack credits remaining for each coach are shown inline next to their sessions

**Given** the Command Center is viewed on mobile
**When** the Start Session button for an `UPCOMING` session is rendered
**Then** it is full-width, minimum 56px height, `--accent-primary` fill, and reachable without any navigation (UX-DR6)
**And** the button is thumb-reachable at the bottom of the screen on mobile viewports

*Dev notes: `platform.booking`. `ScheduleResource`: GET /api/booking/coaches/me/schedule?weekStart={date}, GET /api/booking/parents/me/schedule?playerId={id}. `ProjectedRevenueService.calculateWeeklyRevenue(coachId, weekStart)` reads CONFIRMED + UPCOMING bookings, sums prices, deducts commission from ConfigService. Timezone display logic lives in frontend composable `useTimezone(canonicalTimezone)` — server always returns UTC ISO 8601 strings; frontend formats via `Intl.DateTimeFormat`. Browser timezone detection on login in `auth.store.js`; info bar component `TimezoneNotice.vue` auto-dismisses via `setTimeout`. `SessionPackTracker.vue` reused inline in parent schedule view. Test: `ProjectedRevenueServiceTest` (unit), `ScheduleResourceIT`.*

### Story 3.6: Session Completion — Live Mode & Quick Complete

As a coach,
I want to start and end sessions from the pitch with a minimal one-handed flow and complete a 30-second wrap-up,
So that session records are accurate and development data is captured without interrupting coaching time.

**Acceptance Criteria:**

**Given** a booking is in `UPCOMING` status and the coach taps "Start Session"
**When** the start action is confirmed
**Then** `BookingService.transition(bookingId, START, context)` advances status to `IN_PROGRESS`
**And** the Active Session Screen activates as a full-screen takeover with zero navigation chrome (UX-DR2)
**And** the screen shows: current drill name (42px/800 weight), countdown timer (72px gradient), block progress pips, next drill preview
**And** the "End Session" button is suppressed and not tappable for the first 5 minutes (UX-DR2)

**Given** the Active Session Screen is live
**When** the coach taps "End Session" (available after 5 minutes)
**Then** `BookingService.transition(bookingId, END, context)` advances status to a transient wrap-up state
**And** the 30-second wrap-up sequence begins immediately (UX-DR3)

**Given** the wrap-up sequence begins
**When** Step 1 (Attendance) renders
**Then** a checkbox for each registered player in the session is shown — attendance confirmation is mandatory before advancing
**And** unchecking a player marks them absent for this session

**Given** the coach completes Step 1
**When** Step 2 (Ratings) renders
**Then** Effort, Focus, and Technique star ratings (1–5) are shown one at a time
**And** selecting the 5th star auto-advances to the next rating without a tap — no "Next" button required (UX-DR3)
**And** ratings are qualitative session feedback only — they are NOT fed into the Skills Radar calculation

**Given** the coach completes Step 2
**When** Step 3 (Voice Note) renders
**Then** a microphone button is shown alongside a "Skip" option that is equally visually prominent — skip is not hidden or de-emphasised (UX-DR3)
**And** if recorded, the voice note is transcribed and stored as a text note on the session record

**Given** the coach completes Step 3
**When** Step 4 (Homework) renders
**Then** the 2 most contextually relevant drills from the coach's library are pre-suggested (based on session focus)
**And** the coach can assign 0, 1, or 2 drills — assignment is optional
**And** tapping "Done" on Step 4 finalises the wrap-up

**Given** the wrap-up is finalised
**When** all 4 steps are complete
**Then** `BookingService.transition(bookingId, COMPLETE, context)` advances status to `COMPLETED`
**And** a `BookingCompletedEvent` is published via `ApplicationEventPublisher` (AFTER_COMMIT) for `platform.development` to consume
**And** `SessionPackService.deductCredit(playerId, coachId)` is called — credit is deducted at COMPLETED, not before
**And** the coach is shown the post-wrap-up summary screen: Session DNA mini-radar, SLU headline breakdown, "Development record updated" indicator (UX-DR4)
**And** after 3 seconds the coach is automatically returned to the Command Center

**Given** a coach uses Quick Complete Mode (post-facto completion without Live Mode)
**When** they mark a session complete from the schedule view
**Then** the wrap-up sequence runs identically to Live Mode
**And** status only transitions to `COMPLETED` after the parent or player confirms via `PUT /api/booking/bookings/{id}/confirm-completion`
**And** if no confirmation is received within 24 hours, the session auto-confirms and status transitions to `COMPLETED` (FR-BKG-012)
**And** the parent receives a notification: "Please confirm [Coach]'s session with [Player] on [date]" with Confirm / Dispute actions

*Dev notes: `platform.booking`. `ActiveSessionScreen.vue` — full-screen, `position: fixed`, z-index above all navigation; mounted on `IN_PROGRESS` state. `WrapUpSequence.vue` — 4-step stepper component, auto-advance on 5th star via `@change` watcher. Voice note: browser `MediaRecorder` API → upload via `filestorage` module → transcription stubbed (wired in future). Homework drill suggestions: `GET /api/session/drills/suggestions?sessionId={id}&limit=2` (Epic 4 provides; stub returns empty array at this stage). `BookingCompletedEvent` payload: bookingId, coachId, playerId, sessionId, attendanceMap, ratings (Effort/Focus/Technique), homeworkDrillIds[]. `QuickCompleteConfirmationResource`: PUT /api/booking/bookings/{id}/confirm-completion. Auto-confirm scheduled job: `@Scheduled` in `QuickCompleteTimeoutService`, timeout window from ConfigService. Test: `SessionCompletionServiceTest` (unit — both paths), `WrapUpResourceIT`.*

### Story 3.7: Session Pause & Resume

As a coach,
I want to pause and resume a live session when there is an interruption on the pitch,
So that the session timer accurately reflects net active coaching time and I can restart without losing session state.

**Acceptance Criteria:**

**Given** a booking is in `IN_PROGRESS` status and the coach taps "Pause"
**When** the pause action is confirmed
**Then** `BookingCompletionService.pauseSession(bookingId, coachUserId)` fires `BookingEvent.PAUSE` (COACH) → `PAUSED`
**And** the Active Session Screen freezes the elapsed timer and displays a "PAUSED" indicator
**And** the "End Session" button is hidden while paused — the coach cannot end a paused session

**Given** a booking is in `PAUSED` status and the coach taps "Resume"
**When** the resume action is confirmed
**Then** `BookingCompletionService.resumeSession(bookingId, coachUserId)` fires `BookingEvent.RESUME` (COACH) → `IN_PROGRESS`
**And** the elapsed timer resumes from where it left off — paused duration is excluded from net active time
**And** the "End Session" button visibility follows the net active time rule (≥ 5 minutes of non-paused time)

**Given** a booking is in `IN_PROGRESS` or `PAUSED` status
**When** the coach taps "Pause" or "Resume" respectively
**Then** `BookingStatusChangedEvent` is published and SSE subscribers receive the updated status automatically (no extra wiring required — existing `BookingService.transition()` → `BookingSseService` pipeline handles this)

**Given** a coach pauses and resumes multiple times in one session
**When** the session finally ends via "End Session"
**Then** net active time is the total elapsed time minus all paused durations — tracked client-side via `netActiveSeconds` accumulator in `ActiveSessionScreen.vue`
**And** the 5-minute gate for "End Session" is based on `netActiveSeconds ≥ 300`, not wall-clock time

*Dev notes: `platform.booking`. New migration V34 (after Story 3.6 creates V33): `ALTER TABLE booking.bookings DROP CONSTRAINT chk_bkg_status; ALTER TABLE booking.bookings ADD CONSTRAINT chk_bkg_status CHECK (status IN (...existing values..., 'PAUSED'))`. Add `PAUSED` to `BookingStatus` enum. Add `PAUSE`, `RESUME` to `BookingEvent` enum. State machine: `IN_PROGRESS → PAUSE → PAUSED`, `PAUSED → RESUME → IN_PROGRESS`, `PAUSED → COMPLETE_PENDING → COMPLETED_PENDING_CONFIRMATION` (allow ending from paused state). `EVENT_ROLES` in `BookingService`: `PAUSE → COACH`, `RESUME → COACH`. New endpoints in `SessionCompletionResource`: `POST /api/bookings/{id}/pause` (@PreAuthorize COACH → 204), `POST /api/bookings/{id}/resume` (@PreAuthorize COACH → 204). Frontend: `ActiveSessionScreen.vue` gains pause/resume state: `isPaused = ref(false)`, `pausedAt = ref(null)`, `totalPausedSeconds = ref(0)`; `netActiveSeconds = computed(() => elapsed.value - totalPausedSeconds.value)`; `endAllowed = computed(() => netActiveSeconds.value >= 300)`. Pause button shows when `IN_PROGRESS` and not paused; Resume button shows when paused. `BookingStateChip.vue`: add `PAUSED: { key: 'booking.requests.statusPaused', cls: 'chip--warning' }`. `booking.api.js`: add `pauseSession(id)` and `resumeSession(id)`. `booking.store.js`: add `handlePauseSession()` and `handleResumeSession()`. i18n: add `booking.completion.pause`, `booking.completion.resume`, `booking.completion.paused`, `booking.requests.statusPaused`. Test: `SessionPauseResumeServiceTest` (unit — single pause, multiple cycles, end from paused), `SessionCompletionResourceIT` (extend with pause/resume tests).*

### Story 3.8: Rescheduling, Duplication & Reminders

As a parent or coach,
I want to reschedule sessions, duplicate recurring bookings, and receive timely reminders,
So that scheduling logistics stay low-friction for both sides of a regular coaching relationship.

**Acceptance Criteria:**

**Given** a booking is in `CONFIRMED` or `UPCOMING` status
**When** a parent taps "Request Change" on the session
**Then** a reschedule request is created with a proposed new start and end time (stored in `booking_reschedule_requests`: id UUID, bookingId, proposedBy ENUM PARENT/COACH, proposedStartTime TIMESTAMPTZ, proposedEndTime TIMESTAMPTZ, status ENUM PENDING/ACCEPTED/DECLINED, createdAt TIMESTAMPTZ)
**And** the coach receives a notification: "[Parent] has requested to reschedule [date] to [new date]" with Accept / Decline actions
**And** the original booking remains in its current status — it is not altered until the coach responds (FR-BKG-005)

**Given** a coach accepts a reschedule request
**When** the acceptance is submitted
**Then** the booking's `requestedStartTime` and `requestedEndTime` are updated to the proposed times
**And** the reschedule request status is set to `ACCEPTED`
**And** both parent and coach receive a confirmation notification with the new time
**And** the `BookingStateChip` continues to show the booking's existing state — status does not change for an accepted reschedule

**Given** a coach declines a reschedule request
**When** the decline is submitted
**Then** the reschedule request status is set to `DECLINED`
**And** the original booking times are retained unchanged
**And** the parent is notified that the original time stands

**Given** a coach views a completed booking
**When** they tap "Repeat for next week"
**Then** a new booking record is created in `REQUESTED` status with the same coach, player, and duration — start time advanced by exactly 7 days
**And** the new booking follows the standard request/approval flow from Story 3.3 — it is not auto-confirmed
**And** a notification is sent to the parent: "[Coach] has proposed a repeat session on [date]. Tap to confirm." (FR-BKG-009)

**Given** a booking is in `UPCOMING` status
**When** the time-to-session crosses the reminder intervals read from ConfigService (default: 24h and 2h)
**Then** both coach and parent receive a reminder notification: "Reminder: session with [name] tomorrow at [time, Pitch Timezone]" / "in 2 hours at [time]"
**And** reminders are sent via Web Push (PWA Service Worker) with an in-app fallback notification
**And** reminder delivery is idempotent — re-running the scheduler job does not send duplicate reminders (tracked via `booking_reminders_sent` table: bookingId, intervalKey, sentAt) (FR-BKG-008)

**Given** a parent views their sessions list
**When** a session has a pending reschedule request
**Then** the booking card shows a secondary label "Reschedule requested" in `--accent-warning` beneath the `BookingStateChip`
**And** the original time is shown with a strikethrough alongside the proposed new time

*Dev notes: `platform.booking`. New entities: `booking_reschedule_requests` (id UUID, bookingId UUID, proposedBy ENUM, proposedStartTime TIMESTAMPTZ, proposedEndTime TIMESTAMPTZ, status ENUM PENDING/ACCEPTED/DECLINED, createdAt TIMESTAMPTZ), `booking_reminders_sent` (bookingId UUID, intervalKey VARCHAR, sentAt TIMESTAMPTZ, UNIQUE(bookingId, intervalKey)). `RescheduleResource`: POST /api/booking/bookings/{id}/reschedule, PUT /api/booking/bookings/{id}/reschedule/{rescheduleId}/accept|decline. `BookingDuplicationService.duplicateNextWeek(bookingId)` — creates new `REQUESTED` booking. `BookingReminderService` (@Scheduled, SELECT FOR UPDATE SKIP LOCKED on `booking_reminders_sent`): queries UPCOMING bookings where start time is within reminder window, inserts idempotency record before sending. Web Push via Service Worker `push` event; notification payload built in `NotificationService`. Test: `RescheduleServiceTest` (unit), `BookingReminderServiceTest` (unit — idempotency), `RescheduleResourceIT`.*

---

### Story 3.9: Bulk Session Request from Calendar

As a parent,
I want to select multiple available slots from a coach's calendar and submit them as a single bulk request,
So that I can plan a training schedule upfront and review the total cost before committing.

**Acceptance Criteria:**

**Given** a parent is viewing a coach's availability calendar
**When** they tap an available slot
**Then** it is added to a request basket with a running total (price × selected count)
**And** they can continue selecting up to the platform maximum (`booking.batch.maxSize`, default 5, from ConfigService)
**And** slots that are already requested, booked, or in a pending batch are visually distinguished and non-selectable

**Given** a parent has one or more slots in the basket
**When** they tap "Review requests"
**Then** they see each requested session's date, time, and price; total amount; and a credit preview: "€{creditAvailable} of your credit will be applied — you will be charged €{deficit} via card"
**And** they can remove individual slots before confirming
**And** confirming calls `POST /api/booking/batches`

**Given** the parent confirms the bulk request
**When** `POST /api/booking/batches` is called
**Then** a `booking_batches` record is created: `batchId UUID PK, parentId UUID NOT NULL, coachId UUID NOT NULL, requestedCount INT, totalAmount NUMERIC(10,2) NOT NULL, status ENUM(PENDING/PARTIALLY_ACCEPTED/FULLY_ACCEPTED/DECLINED) NOT NULL DEFAULT 'PENDING', createdAt TIMESTAMPTZ`
**And** one `booking` record per selected slot is created in `REQUESTED` state, each with `batchId` populated
**And** the coach receives one grouped notification: "{parentName} has requested {N} sessions — view and respond"
**And** the parent sees each booking in `REQUESTED` state with an indication they are part of a group

**Given** a coach views their pending booking requests
**When** a bulk request is displayed on their dashboard
**Then** all bookings from the same batch are grouped under the parent's name with session dates listed
**And** they see two response options: "Accept All" (batch action) and individual per-booking accept/decline controls

**Given** the coach taps "Accept All"
**When** `POST /api/booking/batches/{batchId}/accept-all` is called
**Then** only the `REQUESTED` bookings in this batch are affected — any already declined or cancelled are skipped
**And** all eligible bookings transition to `ACCEPTED`
**And** a single `BatchBookingAcceptedEvent(batchId, acceptedBookingIds, parentId, coachId, totalAmount)` is published
**And** `booking_batches.status` transitions to `FULLY_ACCEPTED`
**And** payment for the batch is handled as a single transaction by `PaymentLifecycleService` (Story 7.2)

**Given** a coach accepts or declines bookings from a batch individually (not via "Accept All")
**When** each booking is actioned
**Then** each individually accepted booking publishes a standard `BookingAcceptedEvent` — single-booking payment flow applies (Story 7.2)
**And** when all bookings in the batch reach a terminal state, `booking_batches.status` is updated: `FULLY_ACCEPTED` (all accepted), `PARTIALLY_ACCEPTED` (mixed), `DECLINED` (all declined)
**And** the parent is notified per booking for individual actions

**Given** a coach attempts "Accept All" on a batch they do not own
**When** `POST /api/booking/batches/{batchId}/accept-all` is called
**Then** a `403` is returned — `booking_batches.coachId` must match the authenticated coach

**Given** a parent attempts to submit a batch exceeding `booking.batch.maxSize`
**When** `POST /api/booking/batches` is called
**Then** `400` with `ErrorDto` code `booking.batchSizeExceeded`

*Dev notes: `platform.booking`. New table: `booking_batches` via Flyway migration. `bookings` table: add column `batchId UUID nullable` FK → `booking_batches`. `BookingBatchService.acceptAll()` transitions bookings and publishes `BatchBookingAcceptedEvent`. `BookingBatchResource`: POST /api/booking/batches (`@PreAuthorize` parent), POST /api/booking/batches/{batchId}/accept-all (`@PreAuthorize` coach — service verifies coachId ownership). Individual accept/decline endpoints unchanged. Frontend: `CoachCalendar.vue` gains multi-select mode; basket component shows running total and credit preview (read from `GET /api/payment/credits/balance`); `booking.api.js` adds `createBatch()` and `acceptAllBatch()`. Test: `BookingBatchResourceIT` (create, accept-all, partial individual accept, ownership guard, size limit), `BatchSizeEnforcementTest` (ConfigService boundary).*

### Story 3.10: Session Pack Expiry & Pause Management

As a parent,
I want my session pack credits to carry a defined validity window and to be able to pause them during genuine incapacity,
So that I have clear, fair commitment terms — and both I and the coach are protected from open-ended procrastination.

**Acceptance Criteria:**

**Given** the `session_packs_purchased` table is migrated with `expires_at` and `paused_until` columns (V32)
**When** existing ACTIVE packs are backfilled
**Then** their `expires_at` is set retroactively based on `purchasedAt` and the session count tier thresholds — no existing pack should be left with a null `expires_at`

**Given** `SessionPackService.hasCredits(playerId, coachId)` is evaluated
**When** a pack has `status = 'EXPIRED'` or `paused_until > now()`
**Then** that pack's credits are excluded from the effective credit count — the parent cannot submit booking requests against an expired or currently-paused pack

**Given** an active session pack will expire within 30 days
**When** the expiry warning scheduler runs (every 60 minutes)
**Then** the parent receives a notification: "Your {N} sessions with {Coach} expire on {date} — book them or pause your pack to extend your window"
**And** a second warning fires at 7 days before expiry
**And** each warning is idempotent — sent at most once per threshold per pack (stamp `warning_30d_sent_at`, `warning_7d_sent_at` on the pack row)

**Given** a session pack has `expires_at ≤ now()` and `status = 'ACTIVE'` and `credits_remaining > 0`
**When** the expiry scheduler runs
**Then** `status` transitions to `EXPIRED`
**And** a `SessionPackExpiredEvent` is published (Epic 7 will wire refund flow for unused credits)
**And** the parent receives a notification: "Your {N} unused sessions with {Coach} have expired" with a note that refund eligibility will be reviewed

**Given** a parent has an active session pack with remaining credits and no existing pause on record
**When** they submit a pause request with `pauseStartDate` and `pauseDurationDays` (1–90 days, one pause per pack lifetime)
**Then** the system identifies all bookings for that player+coach with `requestedStartTime` within the pause window and status IN (`REQUESTED`, `ACCEPTED`, `CONFIRMED`, `UPCOMING`)
**And** if conflicting bookings exist: the parent is presented with the full list and must explicitly confirm their cancellation before the pause is applied — the pause is not applied until confirmation is received
**And** if no conflicting bookings exist: the pause is applied immediately

**Given** the parent confirms a pause with one or more conflicting bookings
**When** the pause is applied
**Then** every conflicting booking transitions to `CANCELLED` (a new terminal state distinct from `DECLINED`)
**And** a `BookingCancelledDueToPauseEvent` is published for each cancelled booking
**And** the coach receives an individual cancellation notification per affected booking so their calendar slots are freed for other players
**And** the parent receives a single confirmation listing all cancelled sessions and the new pack expiry date
**And** `paused_until` is set on the pack to `pauseStartDate + pauseDurationDays`
**And** `expires_at` is extended by `pauseDurationDays` so the pause does not consume validity time

**Given** a pause period has elapsed (`paused_until ≤ now()`)
**When** `SessionPackService.hasCredits()` is called
**Then** the pack is treated as fully ACTIVE again — credits are counted normally with no further action required

**Given** a parent attempts to apply a second pause to a pack that already has a pause on record
**When** the pause request is submitted
**Then** `400` with `ErrorDto` code `booking.packAlreadyPaused` — one pause per pack lifetime is the platform limit

*Dev notes: `platform.booking`. V32 migration: add `expires_at TIMESTAMPTZ NOT NULL DEFAULT now()` (backfilled via UPDATE based on session_count tier), `paused_until TIMESTAMPTZ`, `warning_30d_sent_at TIMESTAMPTZ`, `warning_7d_sent_at TIMESTAMPTZ` to `booking.session_packs_purchased`; add `CANCELLED` to `booking.bookings` CHECK constraint (status IN ('REQUESTED','ACCEPTED','CONFIRMED','UPCOMING','DECLINED','CANCELLED','COMPLETED','DISPUTED')). Expiry tier thresholds stored in `platform_config`: `pack.expiry.days.tier1` (1 session → 90), `pack.expiry.days.tier2` (2–5 → 180), `pack.expiry.days.tier3` (6–10 → 365), `pack.expiry.days.tier4` (11+ → 548). `SessionPackService` gains `BookingRepository` dependency (already added in Story 3.3); update `hasCredits()` to also exclude `paused_until > now()` packs. New `SessionPackExpiryScheduler` (`@Scheduled` every 60 min): run expiry transitions and warning notifications. New endpoint `POST /api/bookings/players/{playerId}/packs/{packId}/pause` (`@PreAuthorize` parent ownership) — body: `{ pauseStartDate, pauseDurationDays }`; first call returns `200` with conflict list if bookings exist (no pause applied yet); second call with `{ ..., confirmedCancellationIds: [...] }` applies the pause and triggers cascade cancellations. Each cascade cancellation calls `BookingService.cancelDueToPause(bookingId)` → sets status `CANCELLED`, publishes `BookingCancelledDueToPauseEvent`, resolved via `@TransactionalEventListener` in `BookingEmailListener`. Frontend: `SessionPackDashboard.vue` gains expiry badge, pause CTA, and cancellation confirmation modal. Test: `SessionPackExpirySchedulerTest` (unit — tier boundary, warning idempotency), `SessionPackPauseResourceIT` (pause with conflicts, pause without conflicts, second-pause rejected, cascade cancellation verifiable via booking status check).*

---

## Epic 4: Session Builder & Drill Library

Coaches on Instructor+ can plan sessions using four-block structure, intelligent drill suggestions, Session DNA analysis, drag-and-drop management, equipment packing lists, and session templates. The Foundation 20 platform drill library is live. The 30-second pitch-side wrap-up workflow is operational. Players see their assigned homework drills in the Locker Room.

### Story 4.1: Drill Library Foundation

As a coach,
I want access to a curated platform drill library and a private library for my own drills,
So that I have professionally validated content to build sessions from and a space to store my own coaching material.

**Acceptance Criteria:**

**Given** the `platform.session` module is initialised
**When** the Flyway migration runs
**Then** the `drills` table exists with: id UUID, name VARCHAR, description VARCHAR, libraryType ENUM PLATFORM/COACH, ownerCoachId UUID nullable (null for PLATFORM drills), status ENUM ACTIVE/ARCHIVED, metadata JSONB, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ, `@Version` for optimistic locking
**And** the JSONB `metadata` column stores all 13 drill metadata fields: primarySkills VARCHAR[], secondarySkills VARCHAR[], skillWeighting JSONB, repDensity INT, intensity SMALLINT (1–5), pressureLevel SMALLINT (1–5), cognitiveLoad SMALLINT (1–5), matchRealism SMALLINT (1–5), weakFootBias BOOLEAN, difficultyTier ENUM (U8/U10/U12/U14/U16/U18/AMATEUR/PRO), equipmentRequired VARCHAR[], recommendedGroupSize VARCHAR, coachingPoints VARCHAR[] (3–4 bullets)
**And** a separate `drill_video_refs` table stores the video association: drillId UUID, videoId UUID nullable, refCount INT DEFAULT 1

**Given** the Foundation 20 seed migration runs
**When** the application starts on a fresh database
**Then** 20 Platform Library drills are present across 4 packs: Master Touch (5 drills), Sniper (5 drills), Escape Artist (5 drills), Wall (5 drills)
**And** each drill has all metadata fields populated — no null required fields
**And** `libraryType = PLATFORM` and `ownerCoachId = null` for all 20 drills
**And** `drill_video_refs.videoId` may be null at seed time — video association happens in Story 4.3

**Given** a coach with any subscription tier calls `GET /api/session/drills?library=PLATFORM`
**When** the request is processed
**Then** all active Platform Library drills are returned (libraryType = PLATFORM, status = ACTIVE)
**And** the response includes full metadata for each drill
**And** `@PreAuthorize` verifies the caller is an authenticated coach — guests and parents cannot list drills

**Given** a coach calls `GET /api/session/drills?library=PRIVATE`
**When** the request is processed
**Then** only drills where `ownerCoachId = authenticated coach's id` are returned — never another coach's private drills
**And** if no private drills exist, an empty list is returned (not an error)

**Given** a coach clones a Platform Library drill
**When** they call `POST /api/session/drills/{drillId}/clone`
**Then** a new `drills` record is created with `libraryType = COACH`, `ownerCoachId = authenticated coach's id`, and all metadata copied from the source drill
**And** if the source drill has a video, `drill_video_refs.refCount` on the original is atomically incremented — no physical file is duplicated (FR-VID-007)
**And** the cloned drill is immediately available in the coach's private library

**Given** a coach on Scout tier attempts to create a session using Session Builder
**When** the feature gate is evaluated via `ConfigService`
**Then** the endpoint returns `403 Forbidden` with `ErrorDto` code `security.featureGated`
**And** the Session Builder UI shows the Scout-tier soft teaser overlay with upgrade CTA (UX-DR22) — the drill library browse is still accessible to all tiers for discovery

*Dev notes: Initialize `platform.session` module (package structure, `SessionConfiguration`, SecurityConstants entries, first Flyway migration resolving V-number). `Drill` entity mapped with `@Type(JsonBinaryType.class)` from hypersistence-utils for `metadata` JSONB field. `DrillMetadata` is a Java record. Foundation 20 seed data in a separate Flyway migration (`V{n+2}__foundation_20_drills.sql`) — insert statements with full metadata JSON. `drill_video_refs.refCount` increment/decrement uses `UPDATE ... SET ref_count = ref_count + 1 WHERE drill_id = ?` (atomic SQL, no @Version needed here). `DrillLibraryResource`: GET /api/session/drills, POST /api/session/drills/{id}/clone. `DrillLibraryService` — all reads/writes; Session Builder tier gate checked via `ConfigService.getBoolean("feature.sessionBuilder.enabled.{tier}")`. Test: `DrillLibraryServiceTest` (unit — clone, ref count), `DrillLibraryResourceIT`.*

### Story 4.2: Drill Card & Operations

As a coach,
I want to search, filter, tag, and browse drills using a mobile-first card view with a 15-second autoplay demo,
So that I can quickly identify the right drill for a session without leaving the coaching context.

**Acceptance Criteria:**

**Given** a coach browses the drill library
**When** a `DrillCard` renders for any drill
**Then** it displays: 15-second silent autoplay video loop (or a thumbnail placeholder if no video), drill name, SLU exposure estimate chip (e.g., "~18 SLU"), weak-foot bias indicator (icon shown if `weakFootBias = true`), equipment icons derived from `equipmentRequired[]`, difficulty tier badge, 3–4 coaching point bullets, primary action button ("Add to session" or "Assign" depending on context) (UX-DR14)
**And** the card is mobile-first — all content readable without horizontal scroll on a 375px viewport

**Given** the 15-second autoplay loop is active
**When** the user has `prefers-reduced-motion: reduce` set
**Then** the video does not autoplay — a static thumbnail with a play button is shown instead (UX-DR27)

**Given** a coach enters a search query in the drill library search field
**When** the query is submitted (debounced 300ms)
**Then** `GET /api/session/drills?q={query}&library=PLATFORM|PRIVATE` is called
**And** results matching drill name, description, or coaching points are returned
**And** if no results match, an empty state with icon, "No drills found", and "Clear search" CTA is shown (UX-DR25)

**Given** a coach applies filters to the drill library
**When** one or more of: skill, difficultyTier, equipment, weakFootBias filters are active
**Then** only drills matching all active filters are returned
**And** the active filter count is shown on the filter button so the coach knows filters are applied
**And** filters and search can be combined — both are applied simultaneously

**Given** a coach taps "Add tag" on a private library drill
**When** they enter a tag name and save
**Then** the tag is stored in `drill_tags` (drillId UUID, tag VARCHAR, coachId UUID) and appears on the drill card
**And** tags are coach-specific — Platform Library drills cannot be tagged directly (only their clones can)
**And** existing tags are available as autocomplete suggestions when adding a new tag

**Given** a coach taps "Clone to my library" on a Platform Library drill
**When** the clone is created (Story 4.1 logic)
**Then** a success toast appears: "Added to your library" with a link to the new clone
**And** the DrillCard for the original now shows a "In your library" indicator — the clone action becomes "Edit clone"

**Given** a coach views a drill's detail panel
**When** the panel opens (tap on card body, not primary action)
**Then** the full-size video player, all metadata fields, SLU breakdown by skill (e.g., "DRI: 12 SLU, WEF: 6 SLU"), setup diagram (if present), and all coaching points are shown
**And** the panel is a bottom sheet (max 75% screen height, drag-to-dismiss) on mobile and a side panel on desktop (UX-DR31)

*Dev notes: `platform.session`. New entity: `drill_tags` (drillId UUID, tag VARCHAR, coachId UUID, UNIQUE(drillId, tag, coachId)). `DrillLibraryResource` extended: GET /api/session/drills (add q, skill, difficultyTier, equipment, weakFootBias query params). `DrillCard.vue` — `<video autoplay muted loop playsinline>` with `prefers-reduced-motion` media query disabling autoplay via JS on mount. SLU estimate chip calculated frontend-side from `metadata.repDensity × metadata.skillWeighting` values (no API call). `DrillTagResource`: POST /api/session/drills/{id}/tags, DELETE /api/session/drills/{id}/tags/{tag}. `@PreAuthorize` on tag endpoints — coaches can only tag their own private drills. Test: `DrillSearchServiceTest` (unit — filter combinations), `DrillTagResourceIT`.*

### Story 4.3: Custom Drill Uploads

As a paid-tier coach,
I want to upload short video demos for my own custom drills,
So that my private library has the same visual quality as the platform's Foundation 20 content.

**Acceptance Criteria:**

**Given** a coach on Instructor or Academy tier creates or edits a private library drill
**When** they tap "Upload demo video"
**Then** a pre-flight quota check is performed: `DrillUploadService.checkQuota(coachId)` verifies the coach has not exceeded their storage quota (read from ConfigService by tier)
**And** if the quota check passes, a TUS upload session is initiated via the `platform.video` module
**And** if the quota is exhausted, the upload is rejected with `ErrorDto` code `video.quotaExceeded` before any bytes are transferred

**Given** the pre-flight quota check passes
**When** the coach begins uploading via TUS resumable upload
**Then** a `VideoQuotaReservation` record is created atomically (drillId, coachId, reservedBytes, createdAt) — storage is reserved before upload completes
**And** the reservation expires after the configurable timeout (read from ConfigService, default 60 minutes) if no upload completion webhook is received
**And** a `@Scheduled` job releases expired reservations and restores quota

**Given** the upload completes
**When** the `platform.video` module signals upload complete
**Then** the `drill_video_refs` record for the drill is updated with the videoId
**And** the `VideoQuotaReservation` is converted to a committed quota deduction
**And** the video is associated with the drill and playable via a signed URL

**Given** a Scout tier coach attempts to upload a custom drill video
**When** the upload is initiated
**Then** the request is rejected with `ErrorDto` code `security.featureGated`
**And** the upload button is not shown in the Scout-tier UI — it is absent, not disabled (UX-DR22)

**Given** uploaded drill video constraints
**When** any coach uploads a drill demo
**Then** the video must be ≤120 seconds and ≤500MB — validation enforced server-side before upload begins
**And** if either constraint is violated, the upload is rejected with `ErrorDto` code `video.constraintViolated` specifying which limit was exceeded

**Given** a coach deletes a private drill that has a video
**When** the deletion is confirmed
**Then** `drill_video_refs.refCount` is atomically decremented
**And** if `refCount` reaches 0, a `VideoPhysicalDeletionEvent` is queued — physical deletion from `platform.video` happens asynchronously (never inline in the HTTP request)
**And** if `refCount` > 0 (shared via clones), the physical file is retained and only the drill record is archived

*Dev notes: `platform.session` + `platform.video` module integration. `DrillUploadService` orchestrates: quota stub (`QuotaService.reserveQuota()` interface call — always passes at Epic 4 stage, fully enforced in Epic 6) → TUS initiation via `platform.video` module. Video size/duration constraint validation delegated to `platform.video`'s `VideoTypeConstraints` — no independent limit lookup in this module. `VideoQuotaReservation` entity NOT created here — full reservation lifecycle owned by `platform.video` (Epic 6). `VideoPhysicalDeletionEvent` and `drill_video_refs` ref count management remain in `platform.session`. `DrillUploadResource`: POST /api/session/drills/{id}/video/initiate, DELETE /api/session/drills/{id}/video. Test: `DrillUploadServiceTest` (unit — quota stub, constraint delegation), `DrillUploadResourceIT`.*

### Story 4.4: Session Builder — Block Structure & DNA

As an Instructor or Academy tier coach,
I want to build a structured session with four blocks, live Session DNA scoring, drag-and-drop drill management, and an auto-generated equipment list,
So that every session is purposefully designed before I step onto the pitch.

**Acceptance Criteria:**

**Given** an Instructor+ coach opens Session Builder for a confirmed booking
**When** the builder loads
**Then** four default blocks are pre-populated: Warm-Up (10 min), Technical Foundation (15 min), Game Intensity (25 min), Cool-Down & Review (10 min)
**And** each block shows its duration, assigned drills, and a block-level SLU subtotal
**And** the coach can rename any block, adjust its duration, and add/remove drills independently

**Given** the coach adds a drill to any block
**When** the drill is placed
**Then** the Session DNA panel updates immediately — 5-dimension scores (Technical, Physical, Cognitive, Match Realism, Weak Foot Focus), each 0–100, recalculate based on the aggregate metadata of all drills across all blocks
**And** the `SessionDNAChart` compact thumbnail variant updates in real time without an API call — calculation is client-side from drill metadata
**And** the full-size `SessionDNAChart` variant is shown in the wrap-up summary (Story 3.6)

**Given** the coach wants to reorder drills within or across blocks
**When** they drag a drill card to a new position within the same block or to a different block
**Then** the drill moves to the target position
**And** the Session DNA scores and block SLU subtotals recalculate immediately after the drop
**And** drag-and-drop works on both desktop (mouse) and mobile (touch) — no separate reorder mode required (FR-SES-005)

**Given** the coach saves the session plan
**When** `POST /api/session/sessions` or `PUT /api/session/sessions/{id}` is called
**Then** a `sessions` record is persisted (id UUID, bookingId UUID, coachId UUID, playerId UUID, blocks JSONB, sessionDna JSONB, developmentFocus VARCHAR[], status ENUM DRAFT/SAVED/COMPLETED, createdAt TIMESTAMPTZ)
**And** the equipment packing list is auto-generated from the union of `equipmentRequired[]` across all drills in all blocks and stored on the session record (FR-SES-006)
**And** the list is viewable by the coach before the session as a separate "Equipment" tab

**Given** the coach sets one or more Development Focus areas at session start
**When** the focus is selected
**Then** the Session DNA panel highlights the focus dimensions and the drill suggestion panel (Story 4.5) filters accordingly
**And** the selected focus areas are stored on the session record in `developmentFocus[]`
**And** at least one focus area must be selected before the session can be saved (FR-SES-002)

**Given** a session plan is saved
**When** the equipment list is viewed
**Then** it shows a deduplicated flat list of all required equipment across all drills (e.g., if three drills all require "cones", "cones" appears once)
**And** the list is sorted alphabetically and printable as plain text

*Dev notes: `platform.session`. New entity: `sessions` (id UUID, bookingId UUID, coachId UUID, playerId UUID, blocks JSONB, sessionDna JSONB, equipmentList VARCHAR[], developmentFocus VARCHAR[], status ENUM, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ). `blocks` JSONB structure: `[{ blockType, durationMinutes, drills: [{ drillId, order }] }]`. `sessionDna` JSONB: `{ technical, physical, cognitive, matchRealism, weakFootFocus }` — all 0–100. Session DNA calculation logic in `SessionDnaCalculator` (pure Java, no DB read — takes drill metadata list, returns scores). Equipment list generation in `EquipmentListService.generate(List<DrillMetadata>)` — deduplicate + sort. `SessionBuilderResource`: POST /api/session/sessions, PUT /api/session/sessions/{id}, GET /api/session/sessions/{id}. `SessionDNAChart.vue` — SVG 5-axis radar; compact thumbnail prop for wrap-up cards. Frontend drag-and-drop: Vue Draggable (SortableJS wrapper). Test: `SessionDnaCalculatorTest` (unit — score accuracy), `EquipmentListServiceTest` (unit — deduplication), `SessionBuilderResourceIT`.*

### Story 4.5: Intelligent Drill Suggestions & Session Templates

As an Instructor or Academy tier coach,
I want the session builder to suggest relevant drills and let me save and reuse session templates,
So that planning efficient sessions takes minutes rather than starting from scratch every time.

**Acceptance Criteria:**

**Given** a coach has set a Development Focus for a session
**When** the drill suggestion panel opens
**Then** `GET /api/session/drills/suggestions?sessionId={id}&limit=10` returns drills ranked by relevance to: the selected development focus, neglected skills in the player's SLU history (read from `platform.development` — stub returns empty at this stage if Epic 5 not yet complete), recent training load, the player's age group, and difficulty tier match
**And** suggestions draw from both Platform Library and the coach's private library
**And** the panel is dismissible — the coach can ignore suggestions and build freely

**Given** the suggestion panel returns results
**When** a suggested drill is displayed
**Then** it shows the same `DrillCard` component as the full library view — autoplay loop, SLU chip, equipment icons, coaching points
**And** a single "Add" tap places the drill into the most recently active session block

**Given** the suggestion engine cannot determine relevant drills (no focus set, new player with no history)
**When** the suggestion panel opens
**Then** a fallback set of Foundation 20 drills appropriate for the player's age group and difficulty tier is returned
**And** the panel header reads "Suggested for this age group" — not "Personalised suggestions" — so the coach knows it is a generic fallback

**Given** a coach has a saved session plan they want to reuse
**When** they tap "Save as template"
**Then** a `session_templates` record is created (id UUID, coachId UUID, name VARCHAR, blocks JSONB, sessionDna JSONB, equipmentList VARCHAR[], developmentFocus VARCHAR[], createdAt TIMESTAMPTZ)
**And** the template is stored in the coach's private vault — never visible to other coaches
**And** the template captures the block structure, drill assignments, and DNA snapshot at save time — subsequent changes to drills do not retroactively alter the template

**Given** a coach deploys a template to a specific player booking
**When** they select a template and tap "Use for this session"
**Then** a new `sessions` record is created with blocks and focus copied from the template, linked to the target booking
**And** the coach may customise the deployed copy (add/remove drills, adjust durations) without altering the source template (FR-SES-007)
**And** a visual indicator "Based on template: [name]" is shown at the top of the builder — tapping it navigates back to the template for comparison

**Given** a coach views their template vault
**When** templates are listed
**Then** each template shows name, drill count, Session DNA thumbnail, last deployed date, and deploy count
**And** templates can be renamed or deleted — deleting a template does not affect previously deployed sessions
**And** an empty state with "Save your first session as a template" CTA is shown if no templates exist (UX-DR25)

*Dev notes: `platform.session`. New entity: `session_templates` (id UUID, coachId UUID, name VARCHAR, blocks JSONB, sessionDna JSONB, equipmentList VARCHAR[], developmentFocus VARCHAR[], lastDeployedAt TIMESTAMPTZ nullable, deployCount INT DEFAULT 0, createdAt TIMESTAMPTZ). `DrillSuggestionService.suggest(sessionId, playerId, coachId, limit)` — ranking algorithm: focus match (weight 40%), neglected skill gap (weight 30%, calls `platform.development` API — returns [] if unavailable), age/difficulty fit (weight 20%), recency penalty for recently used drills (weight 10%). `SessionTemplateResource`: GET /api/session/templates, POST /api/session/templates, PUT /api/session/templates/{id}, DELETE /api/session/templates/{id}, POST /api/session/templates/{id}/deploy?bookingId={bookingId}. `deployCount` incremented atomically on deploy. Test: `DrillSuggestionServiceTest` (unit — ranking with/without history), `SessionTemplateResourceIT`.*

### Story 4.6: Homework Assignment & Player Locker Room

As a coach,
I want to assign homework drills to players during the wrap-up,
And as a player, I want to see only my assigned drills in a dedicated Locker Room screen,
So that practice between sessions is structured, focused, and coach-directed.

**Acceptance Criteria:**

**Given** a coach is on Step 4 of the wrap-up sequence (Story 3.6)
**When** the homework picker renders
**Then** up to 2 drills from the coach's library are pre-suggested (from `DrillSuggestionService`, same logic as Story 4.5)
**And** the coach can select 0, 1, or 2 drills — a "Skip homework" option is equally prominent
**And** selected drills are saved to `homework_assignments` (id UUID, sessionId UUID, playerId UUID, coachId UUID, drillId UUID, assignedAt TIMESTAMPTZ, packId UUID — the active session pack at time of assignment)

**Given** homework drills are assigned
**When** the player or parent views the Locker Room
**Then** only drills explicitly assigned to that player by their coaches are shown — the full drill library is never visible to players (FR-SES-009, FR-POR-004)
**And** each assigned drill renders as a `DrillCard` with the 15-second autoplay demo loop, coaching points, and an "I've done this" completion toggle
**And** drills are grouped by coach name if the player has multiple active coaches

**Given** a player taps the "I've done this" toggle on a homework drill
**When** the toggle is saved
**Then** a `homework_completions` record is created (id UUID, assignmentId UUID, playerId UUID, completedAt TIMESTAMPTZ)
**And** the drill card updates to show a "Completed" state with a checkmark — it remains visible but visually distinct
**And** the coach can see completion status on their client detail view

**Given** the player's session pack with the assigning coach is exhausted
**When** the Locker Room renders
**Then** all homework drills assigned under that pack are no longer accessible — the cards are hidden entirely, not shown in a locked state (FR-SES-009)
**And** if the player has active packs with other coaches, those coaches' homework drills remain visible

**Given** a minor player (under 18) is assigned homework drills
**When** the Locker Room renders
**Then** the player sees only their assigned drills — no browsing, no search, no access to full library
**And** `AgePolicyService` enforces this regardless of subscription tier

**Given** the Locker Room has no assigned drills
**When** the empty state renders
**Then** an icon, motivational headline ("Your coach hasn't set homework yet — check back after your next session"), and no drill content is shown (UX-DR25, UX-DR30)
**And** no library browse CTA is present — the Locker Room never redirects to the full drill library

*Dev notes: `platform.session`. New entities: `homework_assignments` (id UUID, sessionId UUID, playerId UUID, coachId UUID, drillId UUID, packId UUID, assignedAt TIMESTAMPTZ), `homework_completions` (id UUID, assignmentId UUID, playerId UUID, completedAt TIMESTAMPTZ). `HomeworkResource`: GET /api/session/players/{playerId}/homework (Locker Room view — filtered by active packs), POST /api/session/homework/{assignmentId}/complete. `@PreAuthorize` on all endpoints: parent ownership guard on player-scoped queries; coach can only read completions for their own assignments. Pack active check: `SessionPackService.hasActivePack(playerId, coachId)` — if false, assignments for that coach are excluded from response. `HomeworkAssignmentService.assign(sessionId, drillIds[])` called from wrap-up finalisation (Story 3.6). `LockerRoom.vue` page component — bottom tab navigation for Player role (UX-DR20). Test: `HomeworkServiceTest` (unit — pack expiry exclusion), `HomeworkResourceIT`.*

---

## Epic 5: Player Development Intelligence

Coaches can track player development through automatic SLU accumulation and Skills Radar assessments. Parents and players see their development dashboard (cumulative SLU, neglected skill alerts, radar chart with baseline comparison). Coaches can generate and share PDF Performance Reports. A unified player timeline records all events across all coaches.

### Story 5.1: SLU Engine & Skill Taxonomy

As a platform operator,
I want SLU (Skill Load Units) automatically calculated and recorded as an immutable snapshot when a session completes,
So that player development data is accurate, tamper-proof, and never affected by future drill metadata changes.

**Acceptance Criteria:**

**Given** the `platform.development` module is initialised
**When** the Flyway migration runs
**Then** a `skill_definitions` table exists (code VARCHAR PK, displayName VARCHAR, displayOrder SMALLINT, active BOOLEAN DEFAULT true) seeded with all 15 skills: PAC, SHO, PAS, DRI, PHY, DEF, WEF, F1T, FIN, 1V1, HED, CRO, IBS, OBS, FKI
**And** new skill codes can be inserted into `skill_definitions` without altering existing `player_skill_stats` rows — extensibility is structural, not code-change-dependent (FR-DEV-001)

**Given** a session transitions to `COMPLETED` and `BookingCompletedEvent` is published
**When** the `@TransactionalEventListener(AFTER_COMMIT)` in `SluCalculationService` fires
**Then** the service reads the `sessions` record (blocks + drill assignments) from `platform.session`
**And** for each skill across all drills in the session, the SLU contribution is calculated: `repDensity × skillWeighting[skill] × intensityModifier × pressureModifier × matchRealismModifier × durationFactor`
**And** modifier scaling factors are read from ConfigService (keys: `slu.intensity.scale`, `slu.pressure.scale`, `slu.matchRealism.scale`) — never hardcoded
**And** one `player_skill_stats` row is written per skill that has a non-zero SLU contribution for the session

**Given** `player_skill_stats` rows are written
**When** any application code attempts to update or delete an existing row
**Then** the operation is rejected — rows are append-only; `player_skill_stats` has no UPDATE path in any repository method
**And** the Flyway migration includes a DB-level comment documenting immutability; the `SluRepository` interface exposes only `save()` and read methods — no `update()` or `delete()` (FR-DEV-003)

**Given** a drill's metadata changes after a session has completed (e.g., admin updates `repDensity`)
**When** the player's historical SLU data is queried
**Then** the historical `player_skill_stats` rows reflect the metadata at the time of the session — they are unaffected by the metadata change
**And** only future sessions using the updated drill metadata will reflect the change

**Given** the `SluCalculationService` reads from `platform.session` to get session drill data
**When** the `sessions` record does not exist or contains no drills
**Then** the listener logs a warning and exits without writing any `player_skill_stats` rows — no exception is thrown that would interfere with the booking completion transaction

**Given** a completed session had no associated session plan (Quick Complete path with no Session Builder usage)
**When** the `BookingCompletedEvent` listener fires
**Then** no `player_skill_stats` rows are written for that session — SLU is only generated from structured Session Builder sessions with drill assignments
**And** the absence of SLU data for a session does not affect the booking's `COMPLETED` status

*Dev notes: Initialize `platform.development` module (package structure, `DevelopmentConfiguration`, SecurityConstants entries, first Flyway migration resolving V-number). New entities: `skill_definitions` (code VARCHAR PK, displayName VARCHAR, displayOrder SMALLINT, active BOOLEAN), `player_skill_stats` (id UUID, playerId UUID, sessionId UUID, coachId UUID, skillCode VARCHAR, sluValue NUMERIC(10,4), calculatedAt TIMESTAMPTZ — no `@Version`, no update methods). `SluCalculationService` — `@TransactionalEventListener(AFTER_COMMIT)` on `BookingCompletedEvent`; calls `SessionQueryService` (thin read-only facade into `platform.session` DB tables — same DB, direct query). `SluFormula` is a pure Java class with no Spring dependencies — takes `DrillMetadata`, returns `Map<String, BigDecimal>` SLU per skill. Modifier values from ConfigService read per-invocation (never cached in field). `SluRepository`: save only — no update/delete methods; annotated with a `// IMMUTABLE: append-only` comment. Test: `SluFormulaTest` (unit — formula accuracy across all 15 skills), `SluCalculationServiceIT` (integration — fires event, verifies rows written).*

### Story 5.2: Skill Exposure Dashboard & Neglected Skill Detection

As a coach or parent,
I want to see a player's weekly skill exposure with trend charts and automatic neglected skill alerts,
So that training gaps are surfaced proactively before they compound over multiple sessions.

**Acceptance Criteria:**

**Given** a coach views a player's development dashboard
**When** the skill exposure panel loads
**Then** cumulative SLU per skill for the current week is displayed across all 15 skills
**And** skills are rendered as a bar chart sorted by exposure volume (highest first) with each bar labelled by skill code and SLU value
**And** a trend line chart below shows weekly SLU totals per skill over the last 8 weeks — one line per skill, togglable by clicking the skill legend
**And** data is aggregated across all coaches — not filtered to the viewing coach's sessions only (FR-DEV-004, FR-POR-008)

**Given** the dashboard loads for a player with less than 2 weeks of session history
**When** the trend chart renders
**Then** available weeks are shown with no error — the chart does not require 8 weeks to be useful
**And** an empty state chip appears on skills with zero SLU ever recorded: "No exposure yet"

**Given** `SluTargetService` evaluates a player's skill exposures against coach-defined weekly targets
**When** a skill's actual weekly SLU falls below its target by more than the configurable threshold (read from ConfigService, default 30%)
**Then** that skill is flagged as neglected in `neglected_skill_flags` (playerId, skillCode, detectedAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable)
**And** the neglected skill is highlighted in amber on the dashboard with label: "[SKILL] below target this week" (FR-DEV-005)
**And** the flag auto-resolves when the skill's actual SLU meets or exceeds target in the following week's evaluation

**Given** a coach views a player's dashboard
**When** they tap "Set weekly targets"
**Then** a target input is shown per skill — numeric SLU value, optional (can be left blank for skills the coach does not target)
**And** targets are stored in `player_slu_targets` (coachId UUID, playerId UUID, skillCode VARCHAR, weeklyTargetSlu NUMERIC, updatedAt TIMESTAMPTZ, UNIQUE(coachId, playerId, skillCode))
**And** multiple coaches can each define independent targets for the same player — they do not overwrite each other (FR-DEV-006)
**And** the neglected skill evaluation uses the highest target set by any coach for each skill

**Given** neglected skill flags exist for a player
**When** the coach views the session builder's drill suggestion panel (Story 4.5)
**Then** drills targeting neglected skills are ranked higher in suggestions — the integration point is `DrillSuggestionService` calling `GET /api/development/players/{playerId}/neglected-skills`
**And** the suggestion panel shows a subtle "Addresses neglected skill" tag on relevant drills

**Given** a parent views their child's development dashboard
**When** the skill exposure section renders
**Then** it displays the same aggregated SLU data and neglected skill alerts as the coach view
**And** a narrative summary is shown above the charts: "Weak foot exposure increased 42% this month" (derived from SLU trend delta) before the raw numbers (UX-DR29)
**And** `@PreAuthorize` parent ownership guard is enforced — a parent can only see their own linked players' data

*Dev notes: `platform.development`. New entities: `player_slu_targets` (coachId UUID, playerId UUID, skillCode VARCHAR, weeklyTargetSlu NUMERIC, updatedAt TIMESTAMPTZ, UNIQUE(coachId, playerId, skillCode)), `neglected_skill_flags` (id UUID, playerId UUID, skillCode VARCHAR, detectedAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable). `SluDashboardService.getWeeklyExposure(playerId, weeksBack)` — aggregate `player_skill_stats` by skillCode and ISO week; sub-second via snapshot table (NFR-001 — no real-time historical joins). `NeglectedSkillDetectionService` — `@Scheduled` weekly job: compares actual SLU vs. targets, writes/resolves flags. `SluTargetResource`: GET/PUT /api/development/players/{playerId}/targets. `SkillExposureResource`: GET /api/development/players/{playerId}/exposure?weeks=8. Narrative summary generated by `SluNarrativeService.generate(playerId)` — computes month-over-month SLU deltas, returns localised string keys for i18n. Test: `NeglectedSkillDetectionServiceTest` (unit — threshold logic, multi-coach target resolution), `SluDashboardResourceIT`.*

### Story 5.3: Skills Radar — Assessment Entry & Multi-Coach Cumulation

As a coach,
I want to enter periodic Skills Radar assessments for my players using a standardised rubric,
So that ability scores are grounded in evidence and accumulate fairly across all coaches working with the same player.

**Acceptance Criteria:**

**Given** an Instructor+ coach opens a player's Skills Radar assessment panel
**When** they start a new assessment entry
**Then** they can enter scores for any subset of the 15 skills — they are not required to assess all 15 in one entry
**And** each score is entered on the 1–100 universal scale with 7 tier labels shown as reference: Elite (90–100), Excellent (80–89), Good (70–79), Above Average (60–69), Average (50–59), Below Average (40–49), Very Weak (<40) (FR-DEV-008)
**And** each skill score field shows the standardised rubric criteria as a tooltip before the coach commits a value

**Given** a coach submits a Skills Radar assessment entry
**When** the entry is saved
**Then** a `radar_assessment_entries` record is created (id UUID, coachId UUID, playerId UUID, skillCode VARCHAR, score SMALLINT 1–100, assessmentDate DATE, assessmentType ENUM OBJECTIVE/MATCH_OBSERVATION/COACH_EVALUATION, notes VARCHAR nullable, createdAt TIMESTAMPTZ)
**And** one record is created per skill assessed in this session — a single "entry event" may contain multiple rows
**And** the entry is linked by a shared `assessmentGroupId UUID` so all skills from one sitting are retrievable together

**Given** multiple coaches have submitted assessments for the same player and skill
**When** the unified Skills Radar score for that skill is calculated
**Then** the composite is computed as: (average of OBJECTIVE entries × 0.50) + (average of MATCH_OBSERVATION entries × 0.30) + (average of COACH_EVALUATION entries × 0.20) — across all coaches, not per-coach
**And** the composite score is stored in `player_radar_composites` (playerId UUID, skillCode VARCHAR, compositeScore NUMERIC(5,2), entryCount INT, lastUpdatedAt TIMESTAMPTZ) and recalculated on every new entry via `@TransactionalEventListener(AFTER_COMMIT)` (FR-DEV-009)
**And** `player_radar_composites` is a materialised snapshot — never calculated at query time

**Given** a coach has entered an assessment for a skill
**When** they view past entries for that skill
**Then** all their own entries are listed chronologically with date, score, assessment type, and notes
**And** they can see the count of other coaches' entries (e.g., "2 other coaches have also assessed this skill") but not their individual scores — coaching assessments are private between coaches

**Given** a Scout tier coach attempts to enter a Skills Radar assessment
**When** the request reaches the endpoint
**Then** it returns `403 Forbidden` with `ErrorDto` code `security.featureGated`
**And** the Skills Radar entry UI is absent from the Scout coach view — not shown in a disabled state (UX-DR22)

**Given** an assessment entry is submitted with a score outside the 1–100 range
**When** the endpoint validates the payload
**Then** it returns `400 Bad Request` with `ErrorDto` field error on the offending score field
**And** no partial entries are saved — all skills in the group succeed or none are saved (`@Transactional`)

*Dev notes: `platform.development`. New entities: `radar_assessment_entries` (id UUID, assessmentGroupId UUID, coachId UUID, playerId UUID, skillCode VARCHAR, score SMALLINT, assessmentDate DATE, assessmentType ENUM, notes VARCHAR, createdAt TIMESTAMPTZ), `player_radar_composites` (playerId UUID PK + skillCode VARCHAR PK, compositeScore NUMERIC(5,2), entryCount INT, lastUpdatedAt TIMESTAMPTZ). `RadarCompositeCalculationService` — `@TransactionalEventListener(AFTER_COMMIT)` on `RadarEntrySubmittedEvent`; recalculates affected skill composites and UPSERTs `player_radar_composites`. Composite calculation query: weighted average across all coaches grouped by assessmentType. `RadarAssessmentResource`: POST /api/development/players/{playerId}/radar/entries, GET /api/development/players/{playerId}/radar/entries?coachId=me. `assessmentGroupId` generated client-side (UUID v4) and submitted with the entry payload. Test: `RadarCompositeCalculatorTest` (unit — weighted average, multi-coach, single-coach), `RadarAssessmentResourceIT`.*

### Story 5.4: Skills Radar Display & Development Correlation

As a coach or parent,
I want to view a player's unified Skills Radar with baseline comparison, confidence indicators, and — on Academy tier — insight into how training volume correlates with ability gains,
So that development decisions are evidence-based and progress is visible at a glance.

**Acceptance Criteria:**

**Given** a coach or parent views a player's Skills Radar
**When** the `SkillsRadarChart` component renders
**Then** it displays an SVG polygon on concentric reference circles for the selected skill subset
**And** each node shows: skill code label, current composite score badge, delta indicator ("↑ +6 since first assessment") when a baseline exists, and a confidence dot (filled = 3+ entries, half-filled = 1–2 entries, empty = no entries)
**And** a last-updated tooltip appears on hover/tap showing the date of the most recent entry for that skill (UX-DR9)

**Given** the radar chart renders with a coach-selected skill subset
**When** the coach selects 5 of the 15 skills to display
**Then** the SVG polygon geometry adjusts dynamically — axis count matches the selected subset, not fixed at 15
**And** deselected skills are hidden from the polygon but their scores remain in the underlying data
**And** the selection is persisted per coach-player pair so it restores on next visit

**Given** a baseline assessment exists (the player's first recorded composite score for a skill)
**When** subsequent assessments are entered and composites update
**Then** the delta indicator on each node shows the change from baseline: positive delta in `--accent-primary`, negative in `--accent-danger`, zero in `--text-secondary`
**And** a "Compare to baseline" toggle shows both the current polygon and a ghost polygon at the baseline values simultaneously

**Given** an accessible screen reader navigates the radar chart
**When** the chart is focused
**Then** a `<table>` element with all node values (skill code, current score, baseline score, delta) is present in the DOM as an accessible alternative — visually hidden but readable by screen readers (UX-DR9, UX-DR18)

**Given** an Academy tier coach views the Development Correlation Engine panel
**When** there is sufficient history (minimum configurable session count, read from ConfigService)
**Then** for each skill the panel shows: cumulative SLU for that skill (volume), Skills Radar composite score (ability), and a correlation insight: "High SLU → Score improvement" / "High SLU → No improvement (technique issue?)" / "Low SLU → Score stable (natural ability?)" (FR-DEV-011)
**And** insights are presented as plain-English sentences, not raw numbers
**And** if insufficient history exists, the panel shows "Not enough data yet — keep logging sessions" with the minimum required session count

**Given** a coach below Academy tier views the Correlation Engine section
**When** the panel would render
**Then** it is shown as a blurred teaser with "Academy feature — upgrade to unlock" CTA (UX-DR22) — never a 403 error in the UI

*Dev notes: `platform.development`. `SkillsRadarChart.vue` — SVG rendered via computed polygon points from `compositeScore` values; dynamic axis geometry using trigonometric point calculation for N-skill subset. Baseline stored in `player_radar_baselines` (playerId UUID, skillCode VARCHAR, baselineScore NUMERIC(5,2), recordedAt TIMESTAMPTZ) — written once per skill on first composite calculation, never overwritten. `RadarDisplayResource`: GET /api/development/players/{playerId}/radar/display (returns composites + baselines + entry counts). `CoachRadarPreferences` entity: (coachId UUID, playerId UUID, selectedSkills VARCHAR[], updatedAt TIMESTAMPTZ). `DevelopmentCorrelationService.getInsights(playerId, coachId)` — Academy gate checked via ConfigService; minimum session threshold from ConfigService. Test: `SkillsRadarChartSpec` (Vue component test — polygon geometry for 5/10/15 skill subsets), `DevelopmentCorrelationServiceTest` (unit — insight classification logic).*

### Story 5.5: PDF Performance Report & Unified Player Timeline

As a coach,
I want to generate a PDF performance report for a player and maintain a unified timeline of all development events,
So that parents have a professional summary to take away and a complete record of their child's journey.

**Acceptance Criteria:**

**Given** an Instructor+ coach views a player's development dashboard
**When** they tap "Generate Report"
**Then** a PDF is generated containing: player name and age, report date, Skills Radar chart (current polygon + baseline ghost), skill score table (skill, baseline, current, improvement %), session count and total SLU, coach's "Next Steps" free-text section (required, max 500 characters), and coach name + verification badge
**And** the report is stored in `performance_reports` (id UUID, coachId UUID, playerId UUID, generatedAt TIMESTAMPTZ, pdfUrl VARCHAR, nextSteps VARCHAR, version INT) and accessible via a signed URL from the `filestorage` module
**And** the parent receives a notification: "[Coach] has shared a performance report for [Player]" with a download link

**Given** an Academy tier coach generates a report
**When** the PDF renders
**Then** the coach's logo (if uploaded) and brand colour appear in the report header — replacing the Skillars default header (FR-DEV-013)
**And** if no logo has been uploaded, the Skillars default header is used — the report always renders, never blocked by missing branding

**Given** a parent views their child's Player Portal
**When** they navigate to "Reports"
**Then** all performance reports generated by any coach for that player are listed with: coach name, generation date, and download button (FR-POR-009)
**And** reports are downloadable as PDF — the parent does not need to be online when opening (download to device)
**And** `@PreAuthorize` parent ownership guard is enforced — a parent can only access reports for their own linked players

**Given** a Scout tier coach attempts to generate a report
**When** the request reaches the endpoint
**Then** it returns `403 Forbidden` with `ErrorDto` code `security.featureGated`
**And** the "Generate Report" button is absent from the Scout coach view (UX-DR22)

**Given** any development event occurs for a player (session completed, homework assigned, video uploaded, assessment entered, milestone reached, payment received, review left)
**When** the event is processed
**Then** a `player_timeline_events` record is appended (id UUID, playerId UUID, eventType ENUM, referenceId UUID, referenceModule VARCHAR, occurredAt TIMESTAMPTZ, metadata JSONB)
**And** the timeline is append-only — events are never edited or deleted (except on GDPR erasure)

**Given** a coach views the Unified Player Timeline
**When** the timeline renders
**Then** events from all coaches and all modules are shown chronologically, newest first
**And** each event type has a distinct icon and plain-English label (e.g., "Session completed with Coach Marcus", "Homework assigned: 2 drills", "Skills Radar updated")
**And** coach access to the timeline expires after the configurable inactivity period (read from ConfigService); after expiry the coach sees "Timeline access expired for this player" — parent retains full access always (FR-DEV-014)

*Dev notes: `platform.development`. New entities: `performance_reports` (id UUID, coachId UUID, playerId UUID, generatedAt TIMESTAMPTZ, pdfUrl VARCHAR, nextSteps VARCHAR, version INT), `player_timeline_events` (id UUID, playerId UUID, eventType ENUM, referenceId UUID, referenceModule VARCHAR, occurredAt TIMESTAMPTZ, metadata JSONB — append-only). PDF generation via `iText` or `OpenPDF` (pure Java, no headless browser); radar chart rendered as SVG then embedded. Report PDF stored via `filestorage` module; served via signed URL. Coach branding: `coach_branding` table (coachId UUID PK, logoUrl VARCHAR nullable, brandColour VARCHAR nullable) — Academy gate checked in `ReportGenerationService`. `PlayerTimelineEventPublisher` — Spring `ApplicationEventPublisher` wrapper; each module publishes typed events consumed by `TimelineEventListener` in `platform.development` via `@TransactionalEventListener(AFTER_COMMIT)`. `PerformanceReportResource`: POST /api/development/players/{playerId}/reports, GET /api/development/players/{playerId}/reports. `PlayerTimelineResource`: GET /api/development/players/{playerId}/timeline. Test: `ReportGenerationServiceTest` (unit — PDF content assertions), `PlayerTimelineResourceIT`.*

### Story 5.6: Parent Development Portal

As a parent,
I want a dedicated development portal for each of my players that shows aggregated progress from all coaches, with the Skills Radar as the hero and session pack status always visible,
So that I have a single complete picture of my child's development without needing to piece it together from multiple sources.

**Acceptance Criteria:**

**Given** a parent navigates to the development portal for a player
**When** the portal loads
**Then** the `SkillsRadarChart` (current composite scores across all coaches) is the hero element — rendered above all other content
**And** below the radar: session pack tracker (credits remaining per coach), a narrative summary ("Your weak foot exposure increased 42% this month"), the skill exposure bar chart, neglected skill alerts (if any), and a link to the player timeline
**And** the layout is narrative-first — plain-English summaries precede raw numbers throughout (UX-DR29)

**Given** the parent has multiple player profiles
**When** they switch between profiles using `ParentChildSwitcher`
**Then** the entire portal reloads for the newly selected player — no data bleeds between profiles
**And** the `ParentChildSwitcher` is always visible in the header — never in a settings menu (UX-DR15, FR-POR-007)

**Given** a parent views a player with sessions from multiple coaches
**When** the Skills Radar and SLU dashboard render
**Then** data is aggregated across all coaches — there is no per-coach filter toggle on this view
**And** the SLU chart legend identifies contributing coaches by first name for context (e.g., "Marcus contributed 68% of DRI exposure this month") (FR-POR-008)

**Given** a parent views the development portal for a player with no completed sessions
**When** the portal renders
**Then** the radar shows an empty polygon with "No assessments yet" on each node
**And** the SLU chart shows an empty state: "Sessions will appear here once your first session is completed"
**And** the session pack section shows the Exhausted state with a "Find a coach" CTA linking to the marketplace (UX-DR25)

**Given** a parent views the "Reports" section of the portal
**When** reports are listed
**Then** all PDF reports generated by any coach for that player appear with coach name, date, and download button (FR-POR-009)
**And** tapping download initiates a direct browser download of the signed PDF URL — no intermediate preview page

**Given** a parent views their child's timeline from the portal
**When** the timeline renders
**Then** all event types are visible (sessions, homework, videos, assessments, payments, reviews)
**And** the parent's view is never subject to coach access expiry — full timeline access is permanent for parents (FR-DEV-014)
**And** each event links to the relevant detail if accessible (e.g., session → session detail, report → PDF download)

*Dev notes: `platform.development` (read-only aggregation) + frontend. `ParentDevelopmentPortalResource`: GET /api/development/parents/me/players/{playerId}/portal — returns composite DTO with radar data, SLU summary, narrative strings, pack credits, neglected flags, report count. `@PreAuthorize` parent ownership on all endpoints. `SluContributionService.getCoachContributions(playerId, skillCode, period)` — aggregates `player_skill_stats` by coachId for the narrative attribution. `ParentDevelopmentPortal.vue` page — bottom tab navigation under Parent role (UX-DR20); `ParentChildSwitcher.vue` in shared header layout triggers `router.push` with `playerId` param on switch, causing full page reload. Coach access expiry check: `TimelineAccessService.hasAccess(coachId, playerId)` — reads configurable inactivity window from ConfigService, compares against last completed session date. Test: `ParentDevelopmentPortalResourceIT` (integration — multi-coach aggregation, ownership enforcement).*

---

## Epic 6: Video Module

Coaches can upload drill demos and review videos. Players (18+) can upload homework practice clips. All uploads flow through the TUS pipeline with three-layer content moderation (Arachnid CSAM + VideoIntel explicit + minor safety gate). Storage and bandwidth quotas are enforced concurrency-safely. Parents approve videos for minor players. Playback is secured with signed URLs. The reactive VideoStatusCard updates live.

### Story 6.1: Video Module Foundation & Quota System

As a platform operator,
I want storage and bandwidth quotas enforced atomically per user before any upload begins,
So that no user can exceed their tier allocation regardless of concurrent upload attempts.

**Acceptance Criteria:**

**Given** the `platform.video` module is initialised
**When** the Flyway migration runs
**Then** the following tables exist:
`video_quotas` (userId UUID PK, storageUsedBytes BIGINT DEFAULT 0, bandwidthUsedBytes BIGINT DEFAULT 0, bandwidthPeriodStart TIMESTAMPTZ),
`video_quota_reservations` (id UUID, userId UUID, videoType ENUM HOMEWORK/DRILL_DEMO/COACH_REVIEW, reservedBytes BIGINT, status ENUM ACTIVE/COMMITTED/RELEASED, createdAt TIMESTAMPTZ, expiresAt TIMESTAMPTZ),
`videos` (id UUID, ownerId UUID, videoType ENUM, status ENUM PENDING/UPLOADED/SCANNING/TRANSCODING/PUBLISHED/LOCKED/ARCHIVED/PURGED/HIDDEN/REJECTED, fileSizeBytes BIGINT nullable, durationSeconds INT nullable, bunnyVideoId VARCHAR nullable, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ, `@Version`)
**And** a `video_quotas` row is auto-created for every new user account on registration (via `@TransactionalEventListener(AFTER_COMMIT)` on `UserRegisteredEvent`)

**Given** all quota values are in ConfigService
**When** `QuotaConfigService.getStorageQuotaBytes(userId)` is called
**Then** it reads the user's current subscription tier and returns the configured quota: Scout 0GB, Instructor 5GB, Academy 20GB (coach tiers); Athlete 2GB, Semi-Pro 4GB, Pro 7GB (player tiers)
**And** monthly bandwidth quotas are similarly read: Scout 5GB, Instructor 50GB, Academy 200GB, Athlete 10GB, Semi-Pro 25GB, Pro 50GB (FR-VID-004, FR-VID-005)
**And** all values are admin-modifiable in `platform_config` without code deployment (FR-VID-014)

**Given** a user initiates an upload
**When** `QuotaService.reserveQuota(userId, videoType, requestedBytes)` is called
**Then** it executes atomically within a single `@Transactional` method: SELECT `storageUsedBytes` FROM `video_quotas` WHERE `userId = ?` FOR UPDATE, checks `storageUsedBytes + requestedBytes ≤ storageQuota`, and if the check passes inserts a `video_quota_reservations` record with `status = ACTIVE` and sets `expiresAt = now() + reservationTimeout` (from ConfigService)
**And** if the check fails, `QuotaExceededException` is thrown and no reservation is created
**And** for a Scout tier coach (0GB storage), the check always fails — `video.quotaExceeded` is returned immediately

**Given** two concurrent upload requests arrive simultaneously for a user at or near their quota limit
**When** both requests hit `QuotaService.reserveQuota()` at the same time
**Then** SELECT FOR UPDATE serialises the two transactions — the second request either reserves successfully (if quota remains) or receives `QuotaExceededException`, never double-reserving beyond the limit (NFR-004, FR-VID-006)

**Given** a reservation was created but the upload webhook was never received
**When** the reservation's `expiresAt` timestamp has passed
**Then** a `@Scheduled` job (`QuotaReservationTimeoutService`) finds all `ACTIVE` reservations past expiry and sets their status to `RELEASED`
**And** `storageUsedBytes` in `video_quotas` is NOT decremented on release — only `COMMITTED` reservations increment it; RELEASED reservations were never committed (FR-VID-017)
**And** the expiry window is read from ConfigService (default 60 minutes) — never hardcoded
**And** the scheduler uses SELECT FOR UPDATE SKIP LOCKED to be safe on multi-node deployments

**Given** the start of a new calendar month
**When** the `@Scheduled` monthly bandwidth reset job runs
**Then** `bandwidthUsedBytes` is reset to 0 and `bandwidthPeriodStart` updated for all `video_quotas` rows
**And** the job is idempotent — running it twice in the same month does not double-reset

**Given** three video types are defined
**When** any upload is initiated
**Then** size and duration constraints are enforced per type: Homework (player, max 60s / 250MB), Drill Demo (coach, max 120s / 500MB), Coach Review (coach, max 300s / 1GB)
**And** all limits are read from ConfigService via `VideoTypeConstraints` record — never hardcoded (FR-VID-001, FR-VID-014)
**And** Story 4.3's `DrillUploadService` delegates constraint validation to this module — no duplicate limit definition exists

*Dev notes: Initialize `platform.video` module (package structure, `VideoConfiguration`, SecurityConstants entries, Flyway migration resolving V-number). `QuotaService.reserveQuota()`, `commitReservation()`, and `releaseReservation()` — all `@Transactional`. Atomic quota increment on commit: `UPDATE video_quotas SET storage_used_bytes = storage_used_bytes + ? WHERE user_id = ?` (raw SQL via `@Query(nativeQuery = true)` — no JPA entity merge). `QuotaReservationTimeoutService`: `@Scheduled(fixedDelayString = "${app.video.reservation-check-interval-ms:60000}")` with SELECT FOR UPDATE SKIP LOCKED. `BandwidthResetService`: `@Scheduled(cron = "0 0 1 * *")`. `VideoTypeConstraints` Java record: `(videoType, maxDurationSeconds, maxSizeBytes)` — single source of truth used by both `platform.video` and `platform.session` drill upload path. `QuotaService` is a Spring `@Service` bean in `platform.video`; Story 4.3's `DrillUploadService` calls it via direct Spring injection (same monolith). Test: `QuotaServiceConcurrencyTest` (integration — two threads competing for last quota slot via Testcontainers), `QuotaReservationTimeoutServiceTest` (unit).*

### Story 6.2: TUS Upload Pipeline

As a coach or eligible player,
I want to upload videos via resumable upload so that large files complete reliably even on poor mobile connections,
And so that the platform tracks each upload through its full lifecycle from initiation to published state.

**Acceptance Criteria:**

**Given** a user calls `POST /api/video/uploads/initiate` with `fileName`, `fileSizeBytes`, `mimeType`, and `videoType`
**When** the request is processed
**Then** a `videos` record is created with `operational_state = UPLOADING` and `video_type` populated from the request
**And** a `video_quota_reservations` record is created in ACTIVE status with `video_type` populated (reservation is ACTIVE from creation — it does not "transition to active" at a later step)
**And** the response includes `videoId`, `uploadSessionId`, `providerUploadId` (Bunny.net video GUID), `signedUploadUrl` (always `https://video.bunnycdn.com/tusupload`), `tusAuthorizationSignature`, `tusAuthorizationExpire`, and `tusLibraryId`
**And** the Bunny.net video is created via `BunnyVideoProviderAdapter.initializeUpload()` (existing `VideoProviderAdapter` implementation in `infrastructure.video` — do NOT create a new `BunnyTusClient` in `infrastructure.bunny`)

**Given** a TUS upload session is active
**When** the client uploads the file using `tus-js-client` directly to `https://video.bunnycdn.com/tusupload`
**Then** the upload is resumable — interrupted uploads resume from the last confirmed byte offset without restarting
**And** `tus-js-client` sends four required Bunny.net TUS headers: `AuthorizationSignature`, `AuthorizationExpire`, `LibraryId`, `VideoId` — all computed server-side and returned in the initiate response
**And** the TUS Upload-Metadata includes `title` (NOT `filename`) and `filetype` — Bunny.net requires `title`

**Given** Bunny.net fires a status-change webhook (JSON body: `{"VideoLibraryId": N, "VideoGuid": "guid", "Status": N}`)
**When** the webhook reaches `POST /api/video/webhooks/bunny`
**Then** HMAC-SHA256 signature in the `X-BunnyStream-Signature` header is verified — unverified webhooks return `400` and are discarded
**And** idempotency is enforced via the `video_webhook_events.event_id` UNIQUE constraint (existing outbox table from Story 6.1 infrastructure — do NOT create a separate `processed_webhooks` table)
**And** `BunnyVideoProviderAdapter.verifyWebhook()` maps the numeric `Status` to internal event strings: 7 → `"video.upload.success"`, 3 → `"video.encoding.success"`, 5 → `"video.encoding.failed"`

**Given** Bunny.net fires Status=7 (PresignedUploadFinished), mapped to `"video.upload.success"`
**When** `WebhookEventProcessorScheduler.processPending()` fires
**Then** `videos.operational_state` transitions `UPLOADING → PROCESSING`
**And** a `VideoUploadedEvent` is published for downstream listeners (Story 6.3 moderation pipeline)

**Given** Bunny.net fires Status=3 (Finished / encoding complete), mapped to `"video.encoding.success"`
**When** `WebhookEventProcessorScheduler.processPending()` fires
**Then** `VideoService.completeTranscoding(videoId)` is called (no duration/storage params — the Bunny webhook body carries only `VideoLibraryId`, `VideoGuid`, `Status`)
**And** `completeTranscoding()` calls `VideoProviderAdapter.getVideoMetadata(providerAssetId)` — a `GET /library/{id}/videos/{id}` request to Bunny.net — to retrieve `length` (seconds, → `videos.duration_ms` × 1000) and `storageSize` (bytes, → `videos.storage_bytes`)
**And** `videos.operational_state` transitions `PROCESSING → READY`
**And** `QuotaProvider.commit(reservationHandle)` is called — reserved bytes become permanently committed storage
**And** a `VideoPublishedEvent(videoId, ownerId)` is published via `ApplicationEventPublisher` (AFTER_COMMIT) for downstream listeners

**Given** Bunny.net fires Status=5 (Failed), mapped to `"video.encoding.failed"`
**When** `WebhookEventProcessorScheduler.processPending()` fires
**Then** `videos.operational_state` transitions `PROCESSING → FAILED`
**And** `QuotaProvider.release(reservationHandle)` is called — reserved quota is restored

**Given** any webhook processing step fails (DB write fails, provider API unreachable)
**When** the failure occurs
**Then** the `video_webhook_events` outbox entry is retried up to `app.video.webhook.max-attempts` times with PENDING status reset
**And** after max attempts the event is dead-lettered to FAILED status and an alert is emitted

*Dev notes: All implementation lives in `platform.video` (service, api, contract) and `infrastructure.video` — reuse existing `BunnyVideoProviderAdapter`, `VideoWebhookResource`, `WebhookEventProcessorScheduler`, `VideoLifecycleService`, and the `video_webhook_events` outbox. V54 migration adds `videos.video_type` column. `QuotaProvider.reserve()` gets a default 3-arg override (adds `videoType` param) — `QuotaService` overrides to populate `video_quota_reservations.video_type`. `WebhookEventProcessorScheduler.dispatchEvent()` switch: `"video.upload.success"` → transition UPLOADING→PROCESSING; `"video.encoding.success"` → `videoService.completeTranscoding(videoId)`; `"video.encoding.failed"` → `videoService.failTranscoding(videoId)`. `VideoService.completeTranscoding()` fetches duration/storage via `videoProviderAdapter.getVideoMetadata()` (non-fatal if call fails), then transitions to READY, commits quota, publishes `VideoPublishedEvent`. Bunny.net TUS auth: SHA-256 (plain, NOT HMAC) of `libraryId + apiKey + expireEpoch + videoGuid` — computed in `BunnyVideoProviderAdapter.computeTusSignature()`. Webhook signature verification: HMAC-SHA256(rawBody, apiKey) read from `X-BunnyStream-Signature` header (not `BunnyCDN-Signature`). Frontend: `tus-js-client` in `video.api.js`; progress + state tracked in `video.store.js`. Test: `VideoUploadPipelineIT` (integration — full UPLOADING→READY flow, encoding.failed release path, metadata-fetch failure non-fatal path).*

### Story 6.3: Content Moderation Pipeline

As a platform operator,
I want every uploaded video scanned through three moderation layers before it can be viewed,
So that CSAM, explicit content, and unsafe minor player videos are blocked automatically and fail safely when moderation services are unavailable.

**Acceptance Criteria:**

**Given** a video transitions to `PROCESSING` state (via `video.upload.success` webhook and `VideoUploadedEvent` publication)
**When** the moderation pipeline is triggered
**Then** `videos.operational_state` transitions to `SCANNING` and Layer 1 (Arachnid CSAM hash check) is initiated asynchronously via `infrastructure.arachnid`
**And** the Arachnid API call is made outside any `@Transactional` boundary — the DB write recording the result happens in a separate `@Transactional` after

**Given** Arachnid returns a CSAM match
**When** the result is processed
**Then** `videos.operational_state` transitions to `LOCKED` immediately
**And** the video owner's account is suspended pending admin review
**And** an urgent admin notification is sent — Arachnid matches are treated as severe and bypass the standard moderation queue
**And** the video is never advanced to Layer 2 — pipeline stops at Layer 1

**Given** Arachnid returns a clean result
**When** Layer 1 clears
**Then** Layer 2 (Google Cloud VideoIntel explicit content detection) is initiated via `infrastructure.videointel`
**And** VideoIntel scanning runs asynchronously — `videos.operational_state` remains `SCANNING` throughout

**Given** VideoIntel flags explicit content with high confidence
**When** the result is processed
**Then** `videos.operational_state` transitions to `LOCKED` and the video is queued in the admin content moderation queue
**And** the owner receives a notification: "Your video has been flagged for review"
**And** the video is not advanced to transcoding

**Given** VideoIntel returns clean
**When** Layer 2 clears
**Then** Layer 3 (minor safety gate) is evaluated: if the video's `ownerId` belongs to a player with age tier < 18, `videos.operational_state` is set to `HIDDEN` and a parental approval request is created (Story 6.6)
**And** if the owner is 18+ or a coach, the video advances directly to `TRANSCODING` — Bunny.net transcoding is triggered via `infrastructure.video`

**Given** any moderation service (Arachnid, VideoIntel) is unavailable
**When** the call fails or times out
**Then** the video stays in `SCANNING` — it is never auto-advanced regardless of timeout duration (fail-closed)
**And** for Arachnid: hold in SCANNING, do not advance, alert admin
**And** for VideoIntel: keep in SCANNING, alert admin, do not auto-publish (FR-TSC-004)
**And** a `@Scheduled` job detects videos stuck in `SCANNING` beyond the SLA window (from ConfigService) and re-queues them for retry; an in-flight lock (`moderation_lock_until` column) prevents the SLA monitor from re-queuing a video whose moderation thread is still actively running

**Given** Arachnid is disabled via feature flag for the current deployment environment
**When** the moderation pipeline reaches Layer 1
**Then** the Arachnid scan is skipped entirely and the pipeline advances directly to Layer 2 (VideoIntel)
**And** no admin alert is raised for the skipped layer — bypass is the expected behaviour in that environment

**Given** VideoIntel is disabled via feature flag for the current deployment environment
**When** the moderation pipeline reaches Layer 2
**Then** the VideoIntel scan is skipped entirely and the pipeline advances directly to Layer 3 (minor safety gate)
**And** no admin alert is raised for the skipped layer — bypass is the expected behaviour in that environment

**Given** both Arachnid and VideoIntel are disabled via feature flags
**When** a video is uploaded and the moderation pipeline is triggered
**Then** the pipeline proceeds through Layer 3 (minor safety gate) only and the video advances to `TRANSCODING` (or `HIDDEN`) as normal
**And** the application functions correctly with no errors, missing routes, or degraded behaviour

**Given** a video's `operational_state` changes at any pipeline stage
**When** the transition completes
**Then** an SSE event is pushed to all connected clients subscribed to that video's status stream (`GET /api/video/{id}/events`)
**And** the `VideoStatusCard` component updates reactively without page reload (FR-VID-020, UX-DR13)
**And** exponential backoff and 2-second polling fallback apply — same pattern as booking SSE (Story 3.4)

*Dev notes: `platform.video` + `infrastructure.arachnid` + `infrastructure.videointel` + `infrastructure.feature`. `ModerationOrchestrationService` triggered by `@TransactionalEventListener(AFTER_COMMIT)` on `VideoUploadedEvent`. Layer execution sequence: Arachnid → VideoIntel → minor safety gate — each layer's result dispatched via `ApplicationEventPublisher` to decouple. All external calls (`ArachnidClient`, `VideoIntelClient`) outside `@Transactional`; state transitions in separate `@Transactional` after. Feature toggles: `infrastructure.feature` controls `ARACHNID_ENABLED` and `VIDEOINTEL_ENABLED` flags — both are per-environment (dev/test/prod) and can be set independently. `ModerationOrchestrationService` checks each flag before invoking the corresponding layer; a disabled layer is silently skipped and the pipeline continues to the next layer as if the layer returned clean. Application must be fully operational with either or both flags off — no fallback stubs, missing beans, or startup failures. `VideoSseService`: `ConcurrentHashMap<UUID, List<SseEmitter>>` keyed by videoId — same pattern as `BookingSseService`. `VideoEventResource`: GET /api/video/{id}/events (SSE). `ModerationSlaMonitorService`: `@Scheduled`, queries videos in `SCANNING` older than SLA window from ConfigService, re-queues via outbox. Frontend: `VideoStatusCard.vue` with `aria-live="polite"` — distinct visual per pipeline state (UX-DR13). Test: `ModerationOrchestrationServiceTest` (unit — all layer outcomes + fail-closed behaviour + both flags off/on combinations), `VideoSseIT` (integration).*

### Story 6.4: Streaming Security & Video Lifecycle

As a platform operator,
I want all video playback secured with short-lived signed URLs and videos automatically progressing through their retention lifecycle,
So that unauthorised access is structurally impossible and storage is reclaimed on schedule.

**Acceptance Criteria:**

**Given** an authorised user requests playback of a `PUBLISHED` video
**When** `GET /api/video/{id}/play` is called
**Then** the application server generates a signed Bunny.net URL with a 2-hour TTL (configurable via ConfigService) — the raw Bunny.net CDN URL is never returned to clients
**And** the signed URL is optionally IP-bound to the requester's IP address (binding enabled/disabled via ConfigService)
**And** Bunny.net Edge Rules are configured to reject requests from domains not on the platform whitelist — delivery is domain-restricted at the CDN level (FR-VID-008, FR-VID-018)

**Given** a `PUBLISHED` coach review video is requested
**When** the signed URL is generated
**Then** HLS delivery is configured with 2-second segment sizes for precise frame-seeking (FR-VID-019)
**And** the `Content-Disposition` header is set to prevent direct download — coach review videos are not downloadable
**And** player homework videos generate a signed URL that allows download by the video owner only (FR-VID-009)

**Given** a user attempts to play a video that is `LOCKED`, `HIDDEN`, `ARCHIVED`, or `PURGED`
**When** the play request is evaluated
**Then** no signed URL is generated — the response is `403 Forbidden` with `ErrorDto` code `video.notAccessible`
**And** the `VideoStatusCard` shows the correct state-specific visual: padlock for LOCKED, warning for HIDDEN, nothing rendered for PURGED (UX-DR13)

**Given** a video's associated subscription or session pack expires (day 0)
**When** the lifecycle job evaluates the video
**Then** `videos.status` transitions to `LOCKED` (day 0–30 post-expiry) — playback is blocked but the file is retained
**And** at day 31, `videos.status` transitions to `ARCHIVED` — the file is moved to cold storage on Bunny.net
**And** at day 90 (or immediately on account deletion), `videos.status` transitions to `PURGED` — the physical file is deleted from Bunny.net and `storageUsedBytes` is decremented accordingly (FR-VID-010)
**And** all lifecycle window values are read from ConfigService — never hardcoded

**Given** a player subscriber on a yearly plan has an active subscription
**When** the lifecycle job evaluates their videos
**Then** videos remain `ACTIVE` for the full duration of the subscription — the 30-day archive policy does not apply to yearly subscribers (FR-PAY-008)
**And** when the yearly subscription expires without renewal, the standard 30-day LOCKED window begins from the expiry date

**Given** the lifecycle `@Scheduled` job runs
**When** it evaluates all videos
**Then** it uses SELECT FOR UPDATE SKIP LOCKED to be safe on multi-node deployments
**And** each video transitions at most one lifecycle state per run — no batch-skipping of states
**And** physical deletion calls to Bunny.net are made outside `@Transactional`; the DB status update to `PURGED` is committed in a separate `@Transactional` after successful deletion

*Dev notes: `platform.video` + `infrastructure.bunny`. `SignedUrlService.generate(videoId, requesterId, requestIp)` — calls `BunnySignedUrlGenerator` in `infrastructure.bunny`; TTL and IP-binding from ConfigService. `BunnyEdgeRulesConfig`: domain whitelist managed in Bunny.net dashboard (not application code) — documented as operational runbook item. `VideoLifecycleService`: `@Scheduled` daily job; queries `videos` where `status = ACTIVE/LOCKED/ARCHIVED` and lifecycle thresholds exceeded; transitions in batches with SELECT FOR UPDATE SKIP LOCKED. `video_lifecycle_log` table: (videoId UUID, fromStatus ENUM, toStatus ENUM, transitionedAt TIMESTAMPTZ) — append-only audit log. Yearly subscriber check: `VideoLifecycleService` reads player subscription tier from `platform.payment`'s `player_subscriptions` table (same DB). `VideoPlayResource`: GET /api/video/{id}/play. Test: `SignedUrlServiceTest` (unit — TTL, IP-binding), `VideoLifecycleServiceTest` (unit — all transitions, yearly subscriber exemption).*

### Story 6.5: Video Privacy, RBAC & Account Deletion Cascades

As a platform operator,
I want all videos private by default, deletion restricted to authorised parties only, and account deletion to cascade a full purge of all owned video assets,
So that no video is ever accessible without explicit authorisation and GDPR erasure obligations are met automatically.

**Acceptance Criteria:**

**Given** any video is uploaded and reaches `PUBLISHED` status
**When** access is evaluated
**Then** the video is private by default — there are no public video feeds and no unauthenticated playback URL exists (FR-VID-009)
**And** `GET /api/video/{id}/play` enforces `@PreAuthorize` — the requester must be the owner, a coach with an active relationship to the owner, or a platform admin
**And** a coach's access to a player's video expires when their active or previous paid relationship ends (configurable window from ConfigService)

**Given** a user attempts to delete a video
**When** `DELETE /api/video/{id}` is received
**Then** the deletion is permitted only if the requester is: the video owner, the verified parent of the owner (player videos), or a platform admin (FR-VID-015)
**And** any other requester receives `403 Forbidden` with `ErrorDto` code `video.deletionNotAuthorised`
**And** on permitted deletion: `videos.status` transitions to `PURGED`, a `VideoPhysicalDeletionEvent` is published AFTER_COMMIT, and `storageUsedBytes` is decremented — physical deletion from Bunny.net is asynchronous (never inline)

**Given** a platform drill is cloned by a coach (Story 4.1)
**When** the coach later deletes their cloned drill's video
**Then** `drill_video_refs.refCount` is atomically decremented (mechanism defined in Story 4.1 — not redefined here)
**And** physical deletion only occurs if `refCount` reaches 0 — shared files are retained until all references are gone (FR-VID-007)
**And** this story adds no new entities for deduplication — it explicitly delegates to the ref-counting mechanism in `platform.session`

**Given** a parent account is deleted
**When** `AccountDeletionRequestedEvent` is published
**Then** a `@TransactionalEventListener(AFTER_COMMIT)` in `platform.video` immediately revokes access to all videos owned by the parent and all their linked player profiles
**And** all owned `videos` records transition to `PURGED` status
**And** `VideoPhysicalDeletionEvent` is queued for each video — Bunny.net physical deletion completes within 30 days (FR-VID-013, FR-VID-016)
**And** `storageUsedBytes` and `bandwidthUsedBytes` in `video_quotas` are reset to 0 for all affected users

**Given** a coach account is deleted
**When** `AccountDeletionRequestedEvent` fires
**Then** the same cascade applies to all videos owned by the coach
**And** drill videos that are shared (refCount > 1) are not physically deleted — only the coach's `drill_video_refs` entry is decremented

**Given** a physical deletion job processes a `VideoPhysicalDeletionEvent`
**When** the Bunny.net deletion API call is made
**Then** it is made outside `@Transactional`
**And** on success, `videos.bunnyVideoId` is nulled and a `video_deletion_log` record is appended (videoId, deletedAt, triggeredBy ENUM USER/ACCOUNT_DELETION/LIFECYCLE)
**And** on Bunny.net API failure, the event is retried via the async outbox pattern (SELECT FOR UPDATE SKIP LOCKED) up to the configurable retry limit

*Dev notes: `platform.video`. No new quota or pipeline entities — this story enforces access policy and deletion RBAC on top of the existing `videos` table. `VideoAccessGuard` Spring `@Component`: `@PreAuthorize("@videoAccessGuard.check(authentication, #videoId)")` — checks ownership, coach relationship, parent linkage, admin role. `AccountDeletionCascadeListener`: `@TransactionalEventListener(AFTER_COMMIT)` on `AccountDeletionRequestedEvent` from `platform.security`. `video_deletion_log` table: (videoId UUID, deletedAt TIMESTAMPTZ, triggeredBy ENUM). Async outbox for physical deletion: `video_deletion_outbox` (id UUID, videoId UUID, bunnyVideoId VARCHAR, status ENUM PENDING/COMPLETED/FAILED, attempts INT, nextRetryAt TIMESTAMPTZ); processed by `VideoDeletionOutboxProcessor` (@Scheduled, SELECT FOR UPDATE SKIP LOCKED). Test: `VideoAccessGuardTest` (unit — all role/ownership combinations), `AccountDeletionCascadeIT` (integration — verifies all owned videos purged).*

### Story 6.6: Player Video Management Portal

As a parent or eligible player,
I want to manage video uploads, see real-time pipeline status, approve videos for minor players, and monitor quota usage,
So that video content is parent-supervised for minors and both players and parents have full visibility and control.

**Acceptance Criteria:**

**Given** a player aged 18+ navigates to their video management screen
**When** the page loads
**Then** an upload button is visible for Homework video type (max 60s / 250MB)
**And** current storage quota usage is shown as a progress bar: used GB / total GB, updated after each upload or deletion (FR-POR-005, FR-POR-012)
**And** current monthly bandwidth usage is shown alongside storage

**Given** a player under 18 navigates to their video management screen
**When** the page loads
**Then** no upload button is present — the upload action is completely absent from the UI, not disabled (FR-POR-011)
**And** the restriction is enforced server-side: `POST /api/video/uploads/initiate` returns `403 Forbidden` with `ErrorDto` code `video.minorUploadProhibited` if the owner is under 18

**Given** a player has uploaded videos
**When** their video list renders
**Then** each video is shown as a `VideoStatusCard` with reactive SSE-driven status updates — PENDING, SCANNING, TRANSCODING, PUBLISHED, LOCKED, HIDDEN, REJECTED states each have a distinct visual treatment (UX-DR13)
**And** `aria-live="polite"` is on each card so screen readers announce status changes

**Given** a minor player uploads a video and it clears moderation (Layer 1 + Layer 2)
**When** the minor safety gate triggers (Story 6.3, Layer 3)
**Then** the video is set to `HIDDEN` and a parental approval request is created in `video_approval_requests` (id UUID, videoId UUID, playerId UUID, parentId UUID, status ENUM PENDING/APPROVED/REJECTED, createdAt TIMESTAMPTZ)
**And** the parent receives a notification: "Approve [Player]'s new video from [context]" with Approve / Reject actions (FR-POR-006)
**And** the `VideoStatusCard` for the player shows "Awaiting parent approval" state

**Given** a parent approves the video
**When** `PUT /api/video/approvals/{approvalId}/approve` is called
**Then** `video_approval_requests.status` transitions to `APPROVED`
**And** `videos.status` transitions to `PUBLISHED` and transcoding is triggered
**And** the player is notified: "Your video has been approved and is now available"

**Given** a parent rejects the video
**When** `PUT /api/video/approvals/{approvalId}/reject` is called
**Then** `video_approval_requests.status` transitions to `REJECTED`
**And** `videos.status` is set to `REJECTED` — the video is invisible to the player and flagged for coach awareness; it is not auto-deleted (FR-VID-003, FR-POR-006)
**And** the `VideoStatusCard` shows a warning overlay distinguishing REJECTED from LOCKED
**And** the parent receives confirmation and the player is notified: "Your video was not approved"

**Given** a user deletes a video from their management screen
**When** `DELETE /api/video/{id}` is called
**Then** RBAC from Story 6.5 is enforced — owner, verified parent, or admin only
**And** the `VideoStatusCard` is removed from the list immediately on successful deletion
**And** quota usage display updates in real time (FR-POR-013)

*Dev notes: `platform.video`. New entity: `video_approval_requests` (id UUID, videoId UUID, playerId UUID, parentId UUID, status ENUM PENDING/APPROVED/REJECTED, createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable). `VideoApprovalResource`: GET /api/video/approvals (parent view — lists pending approvals for all linked players), PUT /api/video/approvals/{id}/approve, PUT /api/video/approvals/{id}/reject. `@PreAuthorize` parent ownership on all approval endpoints. Minor upload prohibition enforced in `VideoUploadService.initiateUpload()` — `AgePolicyService.getAgeTier(ownerId)` checked before quota reservation. Quota display: `GET /api/video/quotas/me` returns current `video_quotas` row for authenticated user — frontend `video.store.js` polls this after upload/delete events. `VideoManagement.vue` page component. Test: `VideoApprovalResourceIT`, `MinorUploadProhibitionIT`.*

---

## Epic 7: Payments & Subscriptions

Parents can purchase session packs and player subscriptions via Stripe Connect. Coaches can accept payments, manage their subscription tier, set pricing (per-session and bundle), and view a transparent revenue dashboard. The refund and cancellation policy matrix is enforced. Reliability strikes are automatically issued on coach no-shows and late cancellations.

### Story 7.1: Stripe Connect Onboarding & Commission Engine

As a coach,
I want to connect my Stripe account to the platform so I can receive payouts,
And as a platform operator, I want an 8% commission automatically deducted from every payment before funds reach the coach.

**Acceptance Criteria:**

**Given** the `platform.payment` module is initialised
**When** the Flyway migration runs
**Then** the following tables exist: `coach_stripe_accounts` (coachId UUID PK, stripeAccountId VARCHAR, onboardingStatus ENUM PENDING/COMPLETE/RESTRICTED, chargesEnabled BOOLEAN DEFAULT false, payoutsEnabled BOOLEAN DEFAULT false, updatedAt TIMESTAMPTZ), `stripe_webhook_events` (eventId VARCHAR PK, eventType VARCHAR, processedAt TIMESTAMPTZ) for idempotency
**And** a `PaymentGateway` interface exists in `platform.payment` — `platform.booking` calls this interface; the Stripe implementation is injected (replacing the stub from Story 3.2)

**Given** a coach navigates to payment settings and has not yet connected Stripe
**When** they tap "Connect with Stripe"
**Then** `GET /api/payment/coaches/me/stripe/onboard` is called and returns a Stripe-hosted OAuth URL
**And** the coach is redirected to the Stripe hosted onboarding flow — no Stripe form is embedded in the platform UI
**And** on return from Stripe, the OAuth `code` is exchanged for a `stripe_account_id` via `stripe.oauth.token()` — this call is outside `@Transactional`
**And** a `coach_stripe_accounts` record is created/updated with the returned `stripeAccountId` and `onboardingStatus = PENDING`

**Given** Stripe fires an `account.updated` webhook
**When** `POST /api/payment/webhooks/stripe` receives the event
**Then** Stripe SDK `Webhook.constructEvent()` verifies the signature before any processing — unverified events return `400` and are discarded
**And** idempotency is enforced: the `eventId` is inserted into `stripe_webhook_events` with ON CONFLICT DO NOTHING — duplicate events are silently acknowledged
**And** `coach_stripe_accounts.chargesEnabled` and `payoutsEnabled` are updated from the event payload
**And** when `chargesEnabled = true` and `payoutsEnabled = true`, `onboardingStatus` transitions to `COMPLETE` and the coach is notified: "Your Stripe account is ready to receive payments"

**Given** a parent purchases a session pack or single session
**When** the payment is processed via `StripePaymentGateway`
**Then** a Stripe Destination Charge is created: `amount = sessionPrice (EUR cents)`, `currency = eur`, `destination = coach.stripeAccountId`, `application_fee_amount = amount × commissionRate` (FR-PAY-002, FR-PAY-004)
**And** the commission rate is read from ConfigService (`platform.commission.rate`, default 0.08) — never hardcoded
**And** the Stripe API call is made outside `@Transactional`; the resulting `chargeId` is stored in `booking_payments` (bookingId UUID, stripeChargeId VARCHAR, stripePaymentIntentId VARCHAR, amount NUMERIC, currency VARCHAR DEFAULT 'eur', commissionAmount NUMERIC, status ENUM, createdAt TIMESTAMPTZ) in a separate `@Transactional` after

**Given** a coach's Stripe account has `onboardingStatus != COMPLETE`
**When** a parent attempts to book a session with that coach
**Then** the booking request is rejected with `ErrorDto` code `payment.coachStripeNotConfigured`
**And** the coach's profile shows a banner: "Complete your Stripe setup to accept bookings"

**Given** any Stripe API call fails (network error, Stripe outage)
**When** the failure is caught
**Then** the exception is caught outside `@Transactional`, an alert is logged, and `ErrorDto` code `payment.providerUnavailable` is returned to the client
**And** no partial DB writes occur — the booking remains in its pre-payment state

*Dev notes: Initialize `platform.payment` module (package structure, `PaymentConfiguration`, SecurityConstants entries, Flyway migration resolving V-number). `StripePaymentGateway implements PaymentGateway` — wires real Stripe SDK replacing the stub from Story 3.2. Stripe SDK `com.stripe:stripe-java`. `StripeWebhookResource`: POST /api/payment/webhooks/stripe — `Webhook.constructEvent(payload, sigHeader, endpointSecret)`. `StripeOnboardingResource`: GET /api/payment/coaches/me/stripe/onboard, GET /api/payment/coaches/me/stripe/status. All Stripe API calls in `StripeClient` wrapper — never called from within `@Transactional`. `booking_payments` table is the source of truth for payment state (not the Stripe dashboard). Test: `StripeWebhookVerificationTest` (unit — valid/invalid signatures), `CommissionCalculationTest` (unit — various amounts and rates), `StripeOnboardingResourceIT`.*

---

### Story 7.2: Session Payment Lifecycle & Credit Wallet

As a parent,
I want payment for accepted sessions to draw from my platform credit balance first and charge only the remaining deficit via Stripe,
So that refunded credits are put to immediate use, and my payment experience is seamless across single bookings, bulk bookings, and session packs.

**Acceptance Criteria:**

**Given** the `platform.payment` module is initialised
**When** the Flyway migration runs
**Then** the following tables exist:
`parent_credit_ledger` (txId UUID PK, parentId UUID NOT NULL, amount NUMERIC(10,2) NOT NULL, type ENUM(BOOKING_DEDUCTION/BOOKING_DEDUCTION_REVERSAL/BOOKING_REFUND/CASH_OUT_DEBIT/STRIPE_FEE_DEBIT) NOT NULL, referenceId UUID, description VARCHAR, createdAt TIMESTAMPTZ NOT NULL) — append-only, never updated or deleted
`stripe_customers` (parentId UUID PK, stripeCustomerId VARCHAR NOT NULL, createdAt TIMESTAMPTZ)
`session_pack_tiers` (packTierId UUID PK, coachId UUID NOT NULL, label VARCHAR NOT NULL, sessionCount INT NOT NULL, totalPrice NUMERIC(10,2) NOT NULL, pricePerSession NUMERIC(10,2) NOT NULL, isActive BOOLEAN NOT NULL DEFAULT true, createdAt TIMESTAMPTZ)
`session_pack_purchases` (purchaseId UUID PK, parentId UUID NOT NULL, coachId UUID NOT NULL, packTierId UUID NOT NULL, pricePerSession NUMERIC(10,2) NOT NULL, remainingSessions INT NOT NULL, expiresAt TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '60 days', extendedAt TIMESTAMPTZ, stripePaymentIntentId VARCHAR, createdAt TIMESTAMPTZ)
`booking_payments` (bookingId UUID PK, batchPaymentIntentId UUID nullable, stripePaymentIntentId VARCHAR nullable, creditDebited NUMERIC(10,2) NOT NULL DEFAULT 0, stripeCharged NUMERIC(10,2) NOT NULL DEFAULT 0, status ENUM(CAPTURED/CHARGE_FAILED/FROZEN) NOT NULL, capturedAt TIMESTAMPTZ, frozenAt TIMESTAMPTZ)
**And** a `parent_credit_balance` view exists: `SELECT parentId, COALESCE(SUM(amount), 0) AS balance FROM parent_credit_ledger GROUP BY parentId`

**Given** a parent checks their credit balance
**When** `GET /api/payment/credits/balance` is called
**Then** returns `{ "balance": 45.00, "currency": "EUR" }` from the `parent_credit_balance` view

**Given** a booking transitions to `ACCEPTED` and is NOT pack-based (`booking.sessionPackPurchaseId IS NULL`)
**When** `PaymentLifecycleService` handles `BookingAcceptedEvent` via `@TransactionalEventListener(phase = AFTER_COMMIT)`
**Then** credit routing is applied against the parent's `parent_credit_balance`:
- Credit ≥ session price → write one `BOOKING_DEDUCTION` ledger entry for the full amount; no Stripe charge
- 0 < credit < session price → write `BOOKING_DEDUCTION` for all available credit; call `PaymentGateway.chargeAndCapture()` for the deficit via Stripe Destination Charge
- Credit = 0 → full Stripe Destination Charge via `PaymentGateway.chargeAndCapture()`
**And** all Stripe calls are made outside `@Transactional`; ledger writes and `booking_payments` update are in a separate `@Transactional` after
**And** on success: `booking_payments.status = CAPTURED`, `capturedAt` set; booking transitions to `CONFIRMED`
**And** on Stripe decline: write a compensating `BOOKING_DEDUCTION_REVERSAL` to restore any credit debited; `booking_payments.status = CHARGE_FAILED`; booking → `DECLINED`; parent shown: "Payment failed — please update your payment method"

**Given** a booking transitions to `ACCEPTED` and IS pack-based (`booking.sessionPackPurchaseId IS NOT NULL`)
**When** `PaymentLifecycleService` handles `BookingAcceptedEvent`
**Then** `PackSessionService.deductSession(sessionPackPurchaseId)` atomically decrements `remainingSessions` — no Stripe charge and no credit ledger entry
**And** booking transitions to `CONFIRMED`
**And** if `remainingSessions = 0` after deduction, parent is notified: "Your session pack is now fully used"
**And** platform credit is never consulted for pack-based bookings — the two payment pools are strictly separate

**Given** a coach taps "Accept All" and `BatchBookingAcceptedEvent` is published (Story 3.8)
**When** `PaymentLifecycleService` handles the event via `@TransactionalEventListener(phase = AFTER_COMMIT)`
**Then** the total amount of all accepted bookings in the batch is computed from the event payload
**And** the same three-case credit routing is applied to the batch total
**And** if a Stripe charge is required, one single Destination Charge PaymentIntent is created for the deficit — not one per booking
**And** one `booking_payments` record is created per booking, each with `batchPaymentIntentId` set to the shared PaymentIntent ID
**And** one credit ledger entry is written if credit was used (type=BOOKING_DEDUCTION, referenceId=batchId)
**And** on success: all bookings transition to `CONFIRMED`
**And** on Stripe decline: `BOOKING_DEDUCTION_REVERSAL` written; all bookings → `DECLINED`; parent notified once: "Batch payment failed — please update your payment method"

**Given** a booking reaches a cancellation terminal state (CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_COACH)
**When** `PaymentLifecycleService` handles the cancellation event
**Then** the refund amount is determined by the cancellation matrix in Story 7.3 — no Stripe refund is ever issued; all monetary refunds go exclusively to the credit wallet
**And** a `parent_credit_ledger` entry is written: type=BOOKING_REFUND, amount=+{refundAmount}, referenceId=bookingId
**And** if the booking is pack-based (sessionPackPurchaseId IS NOT NULL) and the cancellation warrants a session return (>24h): `PackSessionService.restoreSession(sessionPackPurchaseId)` is called instead — no credit entry
**And** if the booking is pack-based and the pack has expired at the time of a coach cancellation (player cannot rebook): monetary credit is written at `session_pack_purchases.pricePerSession` — this is the only circumstance where a pack-based cancellation produces a credit ledger entry
**And** session pack cancellations: expired packs with unused sessions at natural expiry are forfeited with no refund

**Given** a parent purchases a session pack
**When** `POST /api/payment/session-packs/purchase` is called with `{packTierId, paymentMethodId}`
**Then** the coach's `session_pack_tiers` record is validated: `isActive = true` and `coachId` matches the coach being booked
**And** the full `session_pack_tiers.totalPrice` is charged via immediate-capture Stripe Destination Charge
**And** `stripe_customers` is checked first; existing `stripeCustomerId` reused — new Stripe Customer created only if no record exists
**And** Stripe Elements handles card input (`payment.api.js` → `stripe.confirmCardPayment(clientSecret)`) — no raw card data on server
**And** on success: `session_pack_purchases` is created with `pricePerSession` copied from `session_pack_tiers.pricePerSession` at this moment — this value is locked and never changes regardless of future tier repricing
**And** parent is shown at purchase: "Your {N} sessions expire on {expiresAt} — extendable at your coach's discretion"

**Given** a coach reprices a session pack tier
**When** they create a new `session_pack_tiers` record
**Then** the old tier is set to `isActive = false` — it can no longer be purchased but all existing `session_pack_purchases` against it remain valid at their locked `pricePerSession`
**And** `session_pack_tiers` records are never deleted

**Given** a parent attempts to book using an expired session pack
**When** `BookingService` validates the pack on booking creation
**Then** `400` with `ErrorDto` code `payment.packExpired`; parent shown: "This session pack expired on {expiresAt}. Contact your coach if an extension is possible."

**Given** a session pack is within 14 days of `expiresAt` with remaining sessions
**When** the daily expiry scheduler runs
**Then** parent receives: "Your session pack expires on {expiresAt} — {remainingSessions} sessions remaining. Contact your coach if you need an extension."
**And** coach receives: "A session pack for {playerName} expires on {expiresAt} ({remainingSessions} remaining). You may extend it from your dashboard if needed."

**Given** a coach extends a pack via `POST /api/payment/session-packs/{purchaseId}/extend`
**When** validated: `now >= expiresAt - 14 days AND now <= expiresAt AND extendedAt IS NULL AND sessionPackPurchase.coachId == authenticatedCoach.id`
**Then** `expiresAt = expiresAt + INTERVAL '30 days'`, `extendedAt = now`
**And** parent notified: "Your session pack has been extended to {new expiresAt} by your coach"
**And** second extension attempt, window violation, or coach mismatch: `400` ErrorDto `payment.packExtensionNotEligible` or `403`

**Given** a parent requests a credit cash-out via `POST /api/payment/credits/cashout` with `{ "amount": 45.00 }`
**When** the request is processed
**Then** validate `requestedAmount <= parent_credit_balance` — if not: `400` ErrorDto code `payment.insufficientCredit`
**And** calculate fee: `feeAmount = (requestedAmount × feeRate) + feeFixed` where `feeRate` and `feeFixed` come from ConfigService keys `payment.stripe.feeRate` and `payment.stripe.feeFixed`
**And** write two ledger entries atomically: CASH_OUT_DEBIT (−requestedAmount) and STRIPE_FEE_DEBIT (−feeAmount)
**And** call `PaymentGateway.refund(stripePaymentMethodId, netAmount)` outside `@Transactional` for `netAmount = requestedAmount − feeAmount`
**And** parent shown: "€{requestedAmount} credit → €{netAmount} returned to your card (€{feeAmount} processing fee)"

**Given** a parent has no saved payment method
**When** they initiate any Stripe payment flow
**Then** `POST /api/payment/setup-intent` returns a Stripe SetupIntent `clientSecret`; card collected via Stripe Elements; saved `stripePaymentMethodId` stored on `stripe_customers` record

**Given** a booking transitions to `DISPUTED`
**When** `BookingDisputedEvent` is handled
**Then** `booking_payments.status = FROZEN`, `frozenAt` recorded; admin notified via `platform.admin` event; no credit or Stripe action until admin resolves (Story 10.x)

**Given** any Stripe API call fails
**When** caught outside `@Transactional`
**Then** exception logged with booking/batch and payment context; any in-flight credit debit reversed via `BOOKING_DEDUCTION_REVERSAL` ledger entry; `booking_payments.status` left in last valid state; `ErrorDto` code `payment.lifecycleFailure` returned synchronously or admin alert raised for async failures

*Dev notes: `platform.payment`. Stripe Connect account type: **Express**; all charges use the Destination Charge model. `PaymentGateway` interface: `chargeAndCapture(bookingId, parentId, coachId, amount)`, `refund(stripePaymentMethodId, netAmount)`, `freezePayment(paymentIntentId)`, `createSetupIntent(stripeCustomerId)` — these replace `preAuthorise()` and `capturePayment()` from the Story 3.2 stub. `PaymentLifecycleService`: `@TransactionalEventListener(AFTER_COMMIT)` on `BookingAcceptedEvent`, `BatchBookingAcceptedEvent`, `BookingDisputedEvent`; cancellation refund listeners wired in Story 7.3. Credit ledger writes always in their own isolated `@Transactional`. `PackSessionService`: `deductSession(purchaseId)` and `restoreSession(purchaseId)` use `SELECT … FOR UPDATE` on `session_pack_purchases` to prevent concurrent over-decrement. `SessionPackPaymentResource`: POST /api/payment/session-packs/purchase (`@PreAuthorize` parent), POST /api/payment/session-packs/{purchaseId}/extend (`@PreAuthorize` coach — service verifies coachId ownership), POST /api/payment/credits/cashout (`@PreAuthorize` parent), GET /api/payment/credits/balance (`@PreAuthorize` parent), POST /api/payment/setup-intent (`@PreAuthorize` parent). `SessionPackExpiryNotifier`: `@Scheduled` daily, `SELECT … WHERE expiresAt BETWEEN now() AND now() + INTERVAL '14 days' AND extendedAt IS NULL AND remainingSessions > 0`. Frontend: `payment.api.js` — `stripe.confirmCardPayment(clientSecret)`; never call Stripe SDK methods from Pinia stores or Vue components directly. Test: `CreditRoutingTest` (unit — all 3 single-booking cases, batch routing, pack-based bypass, decline reversal), `CashOutServiceTest` (unit — fee calculation, insufficient credit), `BatchPaymentIT` (WireMock — batch charge, all bookings CONFIRMED), `PackExtensionIT` (window boundaries, double-extension guard, ownership), `PackPriceLockedOnPurchaseTest` (unit — repriced tier does not affect existing purchases), `SessionPackPurchaseIT`, `PaymentWebhookIdempotencyIT`.*


---

### Story 7.3: Cancellation, Refund & Reliability Strikes

As a parent,
I want cancellations and no-shows handled automatically according to a clear policy,
And as a coach, I want legitimate cancellations to not damage my reliability score,
And as a platform operator, I want coaches who unexcusedly cancel or no-show to receive strikes that affect their visibility.

**Acceptance Criteria:**

**Given** a parent cancels a single-session booking more than 24 hours before the scheduled start
**When** `BookingService.transition(CANCELLED_PARENT)` is called
**Then** `hoursBeforeSession` is computed as `ChronoUnit.HOURS.between(Instant.now(), booking.startTime.toInstant())` — both values are UTC Instants; the session location's timezone is irrelevant to duration arithmetic
**And** a `BookingCancelledByParentEvent(bookingId, hoursBeforeSession)` is published
**And** `PaymentLifecycleService` writes a `BOOKING_REFUND` credit ledger entry for **100% of the session price** — no fee deduction; this applies to both the >48h and 24-48h windows alike
**And** booking transitions to `CANCELLED_PARENT`; coach is notified: "{parentName} has cancelled the session on {date} — your slot is now free"

**Given** a parent cancels a single-session booking less than 24 hours before the scheduled start
**When** `BookingService.transition(CANCELLED_PARENT)` is called
**Then** no credit is issued — the session fee is forfeited
**And** `booking_payments.status` remains `CAPTURED`; coach earnings are unaffected; coach is notified

**Given** a parent is recorded as a no-show
**When** `BookingService.transition(NO_SHOW_PLAYER)` is called
**Then** no credit is issued — the session fee is forfeited; coach earnings are unaffected

**Given** a coach cancels a booking
**When** `BookingService.transition(CANCELLED_COACH)` is called with a required `cancelReason ENUM(MUTUAL_AGREEMENT/HEALTH_MEDICAL/FAMILY_EMERGENCY/WEATHER/SCHEDULING_PREFERENCE/OTHER_UNEXCUSED)` — a null reason is treated as `OTHER_UNEXCUSED`
**Then** a `BookingCancelledByCoachEvent(bookingId, cancelReason)` is published
**And** `PaymentLifecycleService` writes a `BOOKING_REFUND` credit ledger entry for **100% of the session price** regardless of reason — the platform absorbs any Stripe processing cost
**And** booking transitions to `CANCELLED_COACH`; parent is notified: "Your coach has cancelled the session on {date}. The full amount has been added to your platform credit."
**And** if `cancelReason IN (SCHEDULING_PREFERENCE, OTHER_UNEXCUSED)`: a reliability strike is issued to the coach
**And** if `cancelReason IN (MUTUAL_AGREEMENT, HEALTH_MEDICAL, FAMILY_EMERGENCY, WEATHER)`: no strike is issued; the cancellation is recorded in `coach_cancellation_history` for admin visibility but does not count toward the strike threshold

**Given** a coach is recorded as a no-show
**When** `BookingService.transition(NO_SHOW_COACH)` is called
**Then** a `CoachNoShowEvent` is published
**And** `PaymentLifecycleService` writes a `BOOKING_REFUND` credit ledger entry for **100% of the session price**
**And** a reliability strike is always issued — no-show has no excused category

**Given** a pack-based booking is cancelled by the parent more than 24 hours before the session
**When** `BookingService.transition(CANCELLED_PARENT)` is called with `booking.sessionPackPurchaseId IS NOT NULL`
**Then** `PackSessionService.restoreSession(sessionPackPurchaseId)` increments `remainingSessions`; no credit entry; no Stripe action; coach is notified

**Given** a pack-based booking is cancelled by the parent less than 24 hours before the session, or the parent no-shows
**When** the booking reaches `CANCELLED_PARENT` (<24h) or `NO_SHOW_PLAYER`
**Then** `remainingSessions` is NOT restored — session is forfeited; no credit entry

**Given** a pack-based booking is cancelled by the coach (any reason) or the coach no-shows
**When** the booking reaches `CANCELLED_COACH` or `NO_SHOW_COACH`
**Then** if the pack has NOT expired: `PackSessionService.restoreSession(sessionPackPurchaseId)` — player can rebook; no credit entry
**And** if the pack HAS expired: a `BOOKING_REFUND` credit entry is written at `session_pack_purchases.pricePerSession` — the locked purchase price is used, never the current tier price
**And** strike logic applies as in the single-session ACs: excused coach cancellation = no strike; unexcused or no-show = strike

**Given** a `BookingCancelledByCoachEvent` (unexcused) or `CoachNoShowEvent` is handled
**When** `ReliabilityStrikeService` processes the event
**Then** a `coach_reliability_strikes` record is created: (strikeId UUID PK, coachId UUID NOT NULL, bookingId UUID NOT NULL, reason ENUM(COACH_CANCELLATION_UNEXCUSED/COACH_NO_SHOW) NOT NULL, issuedAt TIMESTAMPTZ, acknowledged BOOLEAN NOT NULL DEFAULT false)
**And** the coach's rolling 30-day strike count is computed: `SELECT COUNT(*) FROM coach_reliability_strikes WHERE coachId = ? AND issuedAt > now() - INTERVAL '30 days'`
**And** if count reaches `reliability.strike.visibilityThreshold` (ConfigService, default 3): `coach_profiles.visibilityStatus = REDUCED`; coach notified: "Your reliability score has been affected. Repeated cancellations reduce your visibility in search results."
**And** if count reaches `reliability.strike.suspensionThreshold` (ConfigService, default 5): `coach_profiles.visibilityStatus = PENDING_REVIEW`; admin notified via `platform.admin` event

**Given** a coach disputes a strike they believe was incorrectly issued
**When** they submit a dispute via the coach dashboard
**Then** `coach_reliability_strikes.acknowledged = true`; admin review triggered (Story 10.x)
**And** the strike continues to count in the rolling window until admin overturns it

**Given** a booking is in `REFUND_PENDING` state following admin dispute resolution (Story 10.x)
**When** admin approves a refund
**Then** `PaymentLifecycleService` writes a `BOOKING_REFUND` credit ledger entry for the admin-determined amount
**And** `booking_payments.status` transitions to `REFUNDED`

*Dev notes: `platform.payment` (`CancellationRefundService`, `ReliabilityStrikeService`) and `platform.booking` (state machine, `cancelReason` parameter). New column on `bookings`: `cancelReason ENUM(MUTUAL_AGREEMENT/HEALTH_MEDICAL/FAMILY_EMERGENCY/WEATHER/SCHEDULING_PREFERENCE/OTHER_UNEXCUSED) nullable` — populated only when coach initiates cancellation; null treated as `OTHER_UNEXCUSED`. New table: `coach_reliability_strikes` (strikeId UUID PK, coachId UUID NOT NULL, bookingId UUID NOT NULL, reason ENUM NOT NULL, issuedAt TIMESTAMPTZ, acknowledged BOOLEAN NOT NULL DEFAULT false). New table: `coach_cancellation_history` (id UUID PK, coachId UUID NOT NULL, bookingId UUID NOT NULL, cancelReason ENUM NOT NULL, createdAt TIMESTAMPTZ) — records ALL coach cancellations including excused for admin pattern visibility. `CancellationRefundService`: `@TransactionalEventListener(AFTER_COMMIT)` on `BookingCancelledByParentEvent`, `BookingCancelledByCoachEvent`, `CoachNoShowEvent`, `PlayerNoShowEvent`; all credit writes in isolated `@Transactional`. `ReliabilityStrikeResource`: GET /api/payment/coaches/me/strikes (`@PreAuthorize` coach), PUT /api/payment/coaches/strikes/{strikeId}/acknowledge (`@PreAuthorize` coach — service verifies ownership). Excused/unexcused classification is a static set check in `ReliabilityStrikeService` — not a DB lookup; the enum drives it. Test: `CancellationRefundMatrixTest` (unit — all parent/coach/no-show paths, single-session and pack-based, expired vs active pack), `ReliabilityStrikeServiceTest` (unit — rolling 30-day window, excused bypass, NO_SHOW always strikes, threshold boundaries), `CoachVisibilitySuppressionIT`, `PackCancellationRefundIT` (expired vs active pack at coach-cancel time).*

---

### Story 7.4: Coach & Player Subscription Tiers

As a coach,
I want to purchase, upgrade, downgrade, and cancel my subscription tier,
And as a parent, I want to manage my player's subscription tier,
So that each party has access to exactly the features their tier entitles them to.

**Acceptance Criteria:**

**Given** the payment module initialises subscription support
**When** the Flyway migration runs
**Then** the following tables exist:
`coach_subscriptions` (subscriptionId UUID PK, coachId UUID NOT NULL UNIQUE, tier ENUM(FREE/STARTER/PRO/ELITE) NOT NULL DEFAULT 'FREE', stripeSubscriptionId VARCHAR, stripeCustomerId VARCHAR, status ENUM(ACTIVE/PAST_DUE/CANCELLED/TRIALLING) NOT NULL DEFAULT 'ACTIVE', currentPeriodEnd TIMESTAMPTZ, cancelAtPeriodEnd BOOLEAN NOT NULL DEFAULT false, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ)
`player_subscriptions` (subscriptionId UUID PK, playerId UUID NOT NULL UNIQUE, tier ENUM(FREE/STANDARD/PREMIUM) NOT NULL DEFAULT 'FREE', stripeSubscriptionId VARCHAR, status ENUM(ACTIVE/PAST_DUE/CANCELLED/TRIALLING) NOT NULL DEFAULT 'ACTIVE', currentPeriodEnd TIMESTAMPTZ, cancelAtPeriodEnd BOOLEAN NOT NULL DEFAULT false, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ)

**Given** a coach on the FREE tier navigates to subscription settings
**When** they view available tiers
**Then** tier feature lists, monthly prices, and annual prices are returned from `GET /api/payment/subscriptions/coach/tiers` — prices and feature entitlements read from ConfigService, never hardcoded
**And** their current tier and `currentPeriodEnd` are shown

**Given** a coach selects a paid tier to subscribe or upgrade to
**When** `POST /api/payment/subscriptions/coach/subscribe` is called with `{tier, billingInterval: MONTHLY|ANNUAL, paymentMethodId}`
**Then** a Stripe Subscription is created with the appropriate `priceId` (looked up from ConfigService by tier + interval) — Stripe API call outside `@Transactional`
**And** `coach_subscriptions.tier` and `stripeSubscriptionId` are updated in a separate `@Transactional` after
**And** `ConfigService` tier entitlements are immediately active for the coach — features gate on `coach_subscriptions.tier`, not on local cache
**And** the coach is notified: "Welcome to {tier}! Your new features are now active."

**Given** a coach upgrades their tier mid-cycle
**When** `POST /api/payment/subscriptions/coach/change-tier` is called with `{newTier}`
**Then** Stripe prorates the charge for the remaining billing period — the proration is handled by Stripe, not calculated by the platform
**And** `coach_subscriptions.tier` is updated immediately after the Stripe call succeeds
**And** new tier entitlements activate immediately

**Given** a coach downgrades their tier mid-cycle
**When** `POST /api/payment/subscriptions/coach/change-tier` is called with `{newTier}` where newTier < currentTier
**Then** the downgrade takes effect at `currentPeriodEnd` — not immediately
**And** a pending downgrade is recorded in `coach_subscription_changes` (changeId UUID PK, coachId UUID, fromTier ENUM, toTier ENUM, effectiveAt TIMESTAMPTZ, applied BOOLEAN DEFAULT false)
**And** `@Scheduled` `SubscriptionChangeApplicator` applies pending changes daily by checking `effectiveAt <= now() AND applied = false`
**And** coach is shown: "Your plan will change to {newTier} on {currentPeriodEnd}. You retain {currentTier} features until then."
**And** if the coach upgrades again before `currentPeriodEnd`, the pending downgrade record is voided (`applied = true`, reason logged)

**Given** a coach cancels their subscription
**When** `DELETE /api/payment/subscriptions/coach` is called
**Then** `stripe.subscriptions.update(id, { cancel_at_period_end: true })` is called — Stripe API call outside `@Transactional`
**And** `coach_subscriptions.cancelAtPeriodEnd = true`; tier remains active until `currentPeriodEnd`
**And** coach is shown: "Your {tier} subscription will end on {currentPeriodEnd}. You retain access until then."
**And** on `customer.subscription.deleted` webhook: `coach_subscriptions.tier = FREE`, `status = CANCELLED`, `stripeSubscriptionId = null`

**Given** a Stripe subscription webhook arrives (`customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_failed`)
**When** `StripeWebhookResource` processes the event (signature verified, idempotency checked via `stripe_webhook_events`)
**Then** `coach_subscriptions` or `player_subscriptions` is updated to reflect the new state
**And** on `invoice.payment_failed`: `status = PAST_DUE`; coach/parent notified: "Your payment failed — please update your payment method to retain access to {tier} features"
**And** on `PAST_DUE` persisting beyond `subscription.pastDue.gracePeriodDays` (ConfigService, default 7): tier downgraded to FREE automatically; coach/parent notified

**Given** a parent manages a player subscription
**When** `POST /api/payment/subscriptions/player/subscribe`, `change-tier`, or `DELETE /api/payment/subscriptions/player` is called
**Then** the same lifecycle applies as coach subscriptions above, scoped to `player_subscriptions` and the parent's `stripeCustomerId`
**And** `@PreAuthorize` verifies the authenticated parent owns the player (PlayerOwnershipGuard) — `403` on mismatch, never `404`

**Given** any feature within the platform checks tier entitlements
**When** a tier-gated operation is attempted
**Then** `ConfigService.getTierEntitlements(userId, userType)` is called at that moment — never cached in a field or stored in the request context
**And** if the user's subscription is `PAST_DUE` or `CANCELLED`, they are treated as FREE tier for entitlement checks

*Dev notes: `platform.payment`. New table: `coach_subscription_changes` (changeId UUID PK, coachId UUID NOT NULL, fromTier ENUM NOT NULL, toTier ENUM NOT NULL, effectiveAt TIMESTAMPTZ NOT NULL, applied BOOLEAN NOT NULL DEFAULT false, voidedAt TIMESTAMPTZ nullable). `SubscriptionResource`: POST /api/payment/subscriptions/coach/subscribe, POST /api/payment/subscriptions/coach/change-tier, DELETE /api/payment/subscriptions/coach, GET /api/payment/subscriptions/coach/tiers, GET /api/payment/subscriptions/coach/me — all `@PreAuthorize` coach. Mirror endpoints under `/player/` — `@PreAuthorize` parent + PlayerOwnershipGuard. `SubscriptionChangeApplicator`: `@Scheduled` daily, `SELECT … FOR UPDATE SKIP LOCKED` on `coach_subscription_changes`. Stripe `priceId` values per tier + interval stored in ConfigService — never hardcoded in service layer. Webhook handler reuses existing `StripeWebhookResource` (Story 7.1); new event types added to the routing switch. Past-due grace period enforced by `SubscriptionGracePeriodChecker` `@Scheduled` daily. Test: `SubscriptionLifecycleIT` (subscribe, upgrade, downgrade with pending change, cancel, webhook processing), `PastDueGracePeriodTest` (unit — grace period boundary, auto-downgrade), `TierEntitlementGatingTest` (unit — PAST_DUE treated as FREE), `PlayerSubscriptionOwnershipIT` (403 on cross-parent access).*

**Cross-story handoffs from Story 6.4 (moved here because `platform.payment` was not yet available):**

The following work was defined in Story 6.4 (`skillars-6-4-streaming-security-video-lifecycle`) but deferred because it requires `platform.payment`. This story must implement all items below before being marked done.

1. **Create `platform.payment.contract.event.SubscriptionExpiredEvent`** — record (or class) with fields: `UUID subscriberId`, `String subscriptionTier` (e.g. `"YEARLY"`, `"MONTHLY"`), `Instant expiredAt`. The `tier` field is critical — `VideoSubscriptionLifecycleListener` routes YEARLY vs non-YEARLY expiry through different code paths (clock reset vs BLOCKED transition). A null `tier` must be treated as non-YEARLY (Path B) with a WARN log.

2. **Implement `VideoSubscriptionLifecycleListener`** (`platform.video.service`) — full Task 4 spec from Story 6.4: `@TransactionalEventListener(AFTER_COMMIT)` on `SubscriptionExpiredEvent`; writes to `subscription_lifecycle_outbox`; `@Scheduled(fixedDelay=60_000)` outbox processor; Path A (YEARLY tier — resets `lifecycle_locked_at`); Path B (non-YEARLY — transitions videos to BLOCKED if no other active subscription). Full spec including pagination, dead-letter handling, and ID bridge between `subscriberId (UUID)` and `video.ownerId (String)` is in Story 6.4 Task 4 notes.

3. **Replace stub `PlayerSubscriptionQueryAdapter`** — the current stub in `platform.booking.adapter.PlayerSubscriptionQueryAdapter` returns `false` for both `hasAnyActiveSubscription()` and `hasActiveYearlySubscription()`. This story must move the adapter to `platform.payment` (or a new `platform.payment.adapter` package) and implement both methods against the `coach_subscriptions` / `player_subscriptions` tables created here. The interface (`PlayerSubscriptionQueryPort`) stays in `platform.video.contract` — no cross-module package imports.

4. **Confirm subscriber ID ↔ video owner ID bridge** — `SubscriptionExpiredEvent.subscriberId` is a `UUID`; `Video.ownerId` is a `String`. Confirm that `subscriberId.toString()` matches the format stored in `videos.owner_id` before calling `videoRepository.findActiveReadyByOwner(ownerId, batchSize)`. If `ownerId` stores a username rather than a UUID string, the lookup will silently return zero results. Document the verified bridge in the Story 6.4 dev notes and in the outbox processor code.

5. **Run the three deferred integration tests from Story 6.4 Task 13** — all depend on `SubscriptionExpiredEvent` existing:
   - `VideoSubscriptionLifecycleListenerIT` — outbox insert, BLOCKED transition, concurrent-subscription guard, clock-reset for YEARLY, outbox at-least-once, dead-letter
   - `YearlyExemptionRenewalIT` — monthly expiry → BLOCKED; yearly active → no ARCHIVED; yearly expires → clock reset to T1; scheduler after T1+30d → ARCHIVED; scheduler-before-outbox race behavior documented
   - `SimultaneousExpiryIT` — MONTHLY+YEARLY concurrent expiry race; documents permanent ACTIVE gap; asserts alert/log produced
   Full test specs are in Story 6.4 Task 13 (`_bmad-output/implementation-artifacts/skillars-6-4-streaming-security-video-lifecycle.md`).

---

### Story 7.5: Revenue Dashboard & Financial Reporting

As a coach,
I want a clear view of my gross earnings, platform commission, Stripe fees, and net payout for any period,
And as a platform operator, I want financial oversight of total transaction volume, commission collected, and refund exposure.

**Acceptance Criteria:**

**Given** a coach navigates to their revenue dashboard
**When** `GET /api/payment/coaches/me/revenue?from={date}&to={date}` is called
**Then** the response contains a `RevenueSummaryDto`: `grossEarnings` (sum of all `booking_payments.stripeCharged + creditDebited` for CAPTURED bookings in the period), `commissionDeducted` (8% of gross, from ConfigService rate), `stripeFees` (estimated from ConfigService fee rate × gross — not pulled from Stripe API), `netPayout` (grossEarnings − commissionDeducted − stripeFees), `sessionCount` (count of CAPTURED bookings), `refundsIssued` (sum of BOOKING_REFUND credit entries linked to this coach's bookings in the period), `currency: "EUR"`
**And** if no period is specified, defaults to the current calendar month
**And** all figures computed from `booking_payments` and `parent_credit_ledger` — never from the Stripe dashboard

**Given** a coach views their transaction list
**When** `GET /api/payment/coaches/me/transactions?from={date}&to={date}&page={n}` is called
**Then** a paginated list of `TransactionDto` is returned, one entry per booking payment: `bookingId`, `playerName`, `sessionDate`, `grossAmount`, `commissionAmount`, `netAmount`, `status`, `creditUsed` (how much of the payment came from parent credit)
**And** cancelled/refunded bookings appear in the list with their refund credit amounts shown

**Given** a coach requests a receipt for a specific booking
**When** `GET /api/payment/coaches/bookings/{bookingId}/receipt` is called
**Then** a `ReceiptDto` is returned containing: booking details, gross amount, commission deducted, net received, session date, player first name (no surname — minor data minimisation), coach name, platform name
**And** the frontend renders this as a printable PDF-ready layout (`ReceiptView.vue`)
**And** the coach must own the booking — `403` if `booking.coachId != authenticatedCoach.id`

**Given** a parent requests a receipt for a booking they paid for
**When** `GET /api/payment/parents/bookings/{bookingId}/receipt` is called
**Then** a `ParentReceiptDto` is returned: booking details, amount charged (credit + Stripe), session date, coach name, player first name
**And** the parent must own the booking — PlayerOwnershipGuard enforced; `403` on mismatch

**Given** a parent views their credit statement
**When** `GET /api/payment/credits/statement?from={date}&to={date}` is called
**Then** a paginated list of `CreditStatementEntryDto` is returned from `parent_credit_ledger`, ordered by `createdAt DESC`: `txId`, `type`, `amount`, `description`, `referenceId`, `createdAt`
**And** the running balance after each entry is computed and included in the response
**And** only entries belonging to the authenticated parent are returned — no cross-parent access

**Given** a platform admin views the financial oversight dashboard
**When** `GET /api/admin/payment/overview?from={date}&to={date}` is called (`@PreAuthorize` admin role)
**Then** the response contains: `totalGrossVolume`, `totalCommissionCollected`, `totalRefundCredit`, `totalCashOuts`, `totalStripeFees` (estimated), `activeCoachSubscriptions` (count by tier), `activePlayerSubscriptions` (count by tier), `subscriptionRevenue`
**And** all figures computed from `booking_payments` and `parent_credit_ledger` — not from Stripe API calls at request time

**Given** a platform admin views coach-level financial detail
**When** `GET /api/admin/payment/coaches/{coachId}/revenue` is called
**Then** the same `RevenueSummaryDto` structure as the coach self-service endpoint is returned, plus `reliabilityStrikeCount` (rolling 30 days) and `outstandingDisputeCount`
**And** `@PreAuthorize` admin role; `404` if coach not found

*Dev notes: `platform.payment`. No new tables — all reporting queries run against existing `booking_payments`, `parent_credit_ledger`, `coach_reliability_strikes`. `RevenueReportingService` contains all aggregation queries — `@Transactional(readOnly = true)`. Stripe fees in reports are **estimated** via ConfigService rates, not fetched from Stripe API. `RevenueResource`: GET /api/payment/coaches/me/revenue, GET /api/payment/coaches/me/transactions, GET /api/payment/coaches/bookings/{id}/receipt — `@PreAuthorize` coach. GET /api/payment/parents/bookings/{id}/receipt, GET /api/payment/credits/statement — `@PreAuthorize` parent. `AdminFinanceResource`: GET /api/admin/payment/overview, GET /api/admin/payment/coaches/{coachId}/revenue — `@PreAuthorize` admin. Frontend: `RevenueDashboard.vue` (coach), `CreditStatement.vue` (parent), `ReceiptView.vue` (printable, both roles); all backed by `payment.store.js`. Pagination: standard `Page<T>` via Spring Data — `page` and `size` query params, default size 20. Test: `RevenueReportingServiceTest` (unit — aggregation correctness across credit+Stripe mixed payments, refund inclusion, period boundaries), `ReceiptOwnershipIT` (403 on cross-coach and cross-parent access), `AdminFinanceResourceIT`.*

---

## Epic 8: Messaging

Coaches, players, and parents communicate through an age-aware, moderated messaging system. All messages pass through a Gemini content moderation gate (fail-closed) before delivery. Parent visibility into minor players' conversations is enforced by the platform. Conversations are retained for 24 months then hard-deleted.

### Story 8.1: Messaging Module Foundation & Conversation Threads

As a coach,
I want to send and receive messages with a player's parent (or with the player directly for older players),
So that session logistics and feedback can be communicated without leaving the platform.

**Acceptance Criteria:**

**Given** the `platform.messaging` module is initialised
**When** the Flyway migration runs
**Then** the following tables exist:
`conversations` (conversationId UUID PK, coachId UUID NOT NULL, playerId UUID NOT NULL, parentId UUID NOT NULL, status ENUM(ACTIVE/ARCHIVED/BLOCKED) NOT NULL DEFAULT 'ACTIVE', createdAt TIMESTAMPTZ, lastMessageAt TIMESTAMPTZ)
`messages` (messageId UUID PK, conversationId UUID NOT NULL FK, senderId UUID NOT NULL, senderRole ENUM(COACH/PARENT/PLAYER) NOT NULL, content TEXT NOT NULL, moderationStatus ENUM(PENDING/APPROVED/BLOCKED/UNDER_REVIEW) NOT NULL DEFAULT 'PENDING', deliveredAt TIMESTAMPTZ nullable, createdAt TIMESTAMPTZ, deletedAt TIMESTAMPTZ nullable)
**And** a unique constraint exists on `conversations(coachId, playerId)` — one conversation thread per coach-player pair

**Given** a booking relationship exists between a coach and a player
**When** either party initiates messaging via `POST /api/messaging/conversations` with `{coachId, playerId}`
**Then** a conversation is created if one does not already exist (upsert on the unique constraint)
**And** if a conversation already exists, the existing `conversationId` is returned — no duplicate threads
**And** `@PreAuthorize` verifies the authenticated user is either the coach or the parent who owns the player; `403` otherwise
**And** if no confirmed booking exists between this coach and player: `403` with `ErrorDto` code `messaging.noBookingRelationship` — messaging is only available between parties with a booking history

**Given** a user wants to view their conversations
**When** `GET /api/messaging/conversations` is called
**Then** a list of `ConversationSummaryDto` is returned for all conversations the authenticated user is a party to: `conversationId`, `otherPartyName`, `otherPartyAvatarUrl`, `lastMessagePreview` (first 60 chars, only if `moderationStatus = APPROVED`), `lastMessageAt`, `unreadCount`
**And** `BLOCKED` conversations are excluded from the list

**Given** a user sends a message
**When** `POST /api/messaging/conversations/{conversationId}/messages` is called with `{ "content": "..." }`
**Then** the sender is verified as a party to the conversation — `403` if not
**And** the message is created with `moderationStatus = PENDING`
**And** `ModerationService.moderate(messageId, content)` is called — in this story the stub `PassThroughModerationService` immediately sets `moderationStatus = APPROVED` and `deliveredAt = now()`; the real implementation is wired in Story 8.3
**And** `conversations.lastMessageAt` is updated
**And** an SSE event `{ type: "NEW_MESSAGE", messageId, conversationId }` is emitted to the recipient's active SSE connection if present

**Given** a user is viewing a conversation
**When** `GET /api/messaging/conversations/{conversationId}/messages?page={n}` is called
**Then** a paginated list of `MessageDto` is returned ordered by `createdAt DESC`: `messageId`, `senderId`, `senderRole`, `content` (null if `moderationStatus = BLOCKED`), `moderationStatus`, `deliveredAt`, `createdAt`
**And** `deletedAt IS NOT NULL` messages are excluded from results
**And** unread messages for the authenticated user are marked as read

**Given** a user opens a conversation
**When** `GET /api/messaging/conversations/{conversationId}/events` is called (SSE)
**Then** an `SseEmitter` is registered in `MessagingEmitterRegistry` (ConcurrentHashMap keyed by userId)
**And** the SSE connection follows the same pattern as `platform.booking` and `platform.video`: heartbeat every 30s, timeout 5 minutes, connection removed from registry on completion/timeout
**And** frontend applies exponential backoff on reconnect: 1s → 2s → 4s → max 30s; falls back to 2s polling after 3 consecutive failures

**Given** a message content is empty or exceeds 2000 characters
**When** `POST /api/messaging/conversations/{conversationId}/messages` is called
**Then** `400` with `ErrorDto` code `messaging.invalidContent`

*Dev notes: `platform.messaging`. `ModerationService` interface introduced here with a stub `PassThroughModerationService` implementation (always sets APPROVED) — Story 8.3 provides the real Gemini implementation injected via `@Primary`. `MessagingResource`: POST /api/messaging/conversations, GET /api/messaging/conversations, POST /api/messaging/conversations/{id}/messages, GET /api/messaging/conversations/{id}/messages, GET /api/messaging/conversations/{id}/events — all `@PreAuthorize` authenticated. `MessagingEmitterRegistry`: `ConcurrentHashMap<UUID, SseEmitter>` bean in `MessagingConfiguration`. `conversations` upsert: `INSERT … ON CONFLICT (coachId, playerId) DO UPDATE SET lastMessageAt = EXCLUDED.lastMessageAt RETURNING *`. Booking relationship check: cross-module query via `BookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(coachId, playerId, CONFIRMED_STATES)` — messaging depends on booking contract only. Test: `ConversationResourceIT` (create, upsert, send, paginate, SSE registration), `MessagingAccessControlIT` (non-party 403, no-booking-relationship 403).*

---

### Story 8.2: Age-Tiered Messaging Access & Parent Visibility

As a platform operator,
I want messaging access and parental oversight enforced automatically by the player's age tier,
So that under-13 players never communicate directly with coaches and parents of 13–17 players always retain read access.

**Acceptance Criteria:**

**Given** a message is sent to a conversation
**When** `MessagingService.sendMessage()` is called
**Then** `AgePolicyService.getAgeTier(conversation.playerId)` is called to determine access rules — this call is never cached; age tier is evaluated fresh on every send
**And** the following rules are enforced:

| Age tier | Player can send | Parent can send | Coach can send | Parent read access |
|---|---|---|---|---|
| U10 / 10–12 (under 13) | ✗ blocked | ✓ | ✓ | Automatic — parent is the primary participant |
| 13–17 | ✓ | ✓ | ✓ | Automatic — parent has oversight visibility |
| 18+ | ✓ | ✓ | ✓ | None by default — parent is not surfaced the conversation |

**And** if a PLAYER role attempts to send in a U10 or 10–12 conversation: `403` with `ErrorDto` code `messaging.playerDirectMessagingRestricted`
**And** the coach's conversation label reflects the tier: under-13 conversations display "Parent of {playerFirstName}" as the other party name; 13–17 display "{playerFirstName} & parent"; 18+ display "{playerFirstName}"

**Given** a coach views their conversation list
**When** `GET /api/messaging/conversations` is called by an authenticated coach
**Then** all conversations are returned regardless of player age tier — the coach sees everyone they have a conversation with
**And** the `otherPartyName` field reflects the age-tier label above

**Given** a player views their conversation list
**When** `GET /api/messaging/conversations` is called by an authenticated player
**Then** only conversations where the player's age tier is 13–17 or 18+ are returned — under-13 players receive an empty list (the conversation exists in the DB but is not surfaced to them)

**Given** a parent views their conversation list
**When** `GET /api/messaging/conversations` is called by an authenticated parent
**Then** conversations where the parent is the primary participant (player age under 13) are returned — the parent sees themselves as the direct party, not as an observer
**And** conversations where the parent has oversight access (player age 13–17) are also returned, labelled with "{playerFirstName}'s conversation with {coachName}"
**And** conversations for adult players (18+) are NOT returned in the parent's list — the parent has no automatic visibility

**Given** a parent wants to read a minor player's conversations via the parental oversight endpoint
**When** `GET /api/messaging/players/{playerId}/conversations` is called
**Then** `@PreAuthorize` parent role is required; PlayerOwnershipGuard verifies `conversations.parentId == authenticatedParent.id` — `403` on mismatch, never `404`
**And** if the player's age tier is 18+: `403` with `ErrorDto` code `messaging.parentalOversightNotApplicable` — adult player conversations are not surfaced to parents
**And** if the player's age tier is under 13 or 13–17: all conversations for that player are returned

**Given** a parent reads messages in a minor player's conversation via `GET /api/messaging/players/{playerId}/conversations/{conversationId}/messages`
**When** the request is processed
**Then** PlayerOwnershipGuard is enforced (`conversations.parentId == authenticatedParent.id`) — `403` on mismatch
**And** all APPROVED messages are returned regardless of `senderRole` — parent sees the full thread
**And** reading messages via this endpoint does NOT mark them as read for the player — unread counts are per-user

**Given** a player turns 13 or 18 and their age tier changes
**When** subsequent messages are sent to existing conversations
**Then** the new age tier rules apply to all future messages in that conversation — existing message history is unaffected
**And** no automatic notification is sent about the tier change; the coach sees the updated `otherPartyName` label on next load

**Given** a parent attempts to send a message in an 18+ player's conversation
**When** `POST /api/messaging/conversations/{conversationId}/messages` is called with `senderRole = PARENT`
**Then** `403` with `ErrorDto` code `messaging.parentMessagingRestrictedForAdult` — parents cannot send messages in adult player conversations
**And** for conversations that transitioned from 13–17 to 18+ on the player's birthday, the restriction applies to new messages only — historical messages are retained

*Dev notes: `platform.messaging`. `AgePolicyService` is in `platform.{module}.service` — called from `MessagingService`, never from `MessagingResource` directly. Age tier rules implemented as a static `AgeMessagingPolicy` enum or strategy class in `platform.messaging.service` — not inline if-else. New endpoints in `MessagingResource`: GET /api/messaging/players/{playerId}/conversations (`@PreAuthorize` parent role + PlayerOwnershipGuard), GET /api/messaging/players/{playerId}/conversations/{conversationId}/messages (same guards). Conversation list query for parents: service-layer union of primary-participant query (`parentId = ? AND ageTier IN (U10, AGE_10_12)`) and oversight query (`parentId = ? AND ageTier IN (AGE_13_17)`) — two separate queries merged in service, not a SQL UNION. Age tier is NOT stored on `conversations` — always derived from `AgePolicyService` at query time to reflect current player age. Test: `AgeMessagingPolicyTest` (unit — all tier/role combinations), `ParentalOversightResourceIT` (ownership guard, adult player 403, minor player visibility), `AgeTierTransitionTest` (unit — rules applied to future sends after tier change).*

---

### Story 8.3: Gemini Content Moderation

As a platform operator,
I want all messages moderated by Gemini before delivery,
So that harmful, abusive, or inappropriate content is blocked before it reaches any user, especially minors.

**Acceptance Criteria:**

**Given** the Gemini moderation implementation is deployed
**When** the application context starts
**Then** `GeminiModerationService` is annotated `@Primary` and `@Service`, replacing the `PassThroughModerationService` stub from Story 8.1 — the stub remains in the codebase with no `@Primary` annotation and is used only in tests where Gemini is not wired

**Given** a message is submitted
**When** `ModerationService.moderate(messageId, content)` is called
**Then** a Gemini API call is made OUTSIDE the `@Transactional` boundary — Gemini I/O must not hold a DB transaction open
**And** after the Gemini call completes, a new `@Transactional` write updates `messages.moderationStatus` and conditionally sets `messages.deliveredAt`
**And** the Gemini prompt instructs the model to evaluate the content against: hate speech, sexual content, threats or violence, personal contact information solicitation, advertising or spam, content inappropriate for minors; the prompt is stored in application properties — never hardcoded in source

**Given** Gemini returns a SAFE verdict for a message
**When** the moderation result is processed
**Then** `messages.moderationStatus` is set to `APPROVED` and `messages.deliveredAt = now()`
**And** an SSE event `{ type: "NEW_MESSAGE", messageId, conversationId }` is emitted to the recipient — delivery is only triggered after APPROVED

**Given** Gemini returns an UNSAFE verdict
**When** the moderation result is processed
**Then** `messages.moderationStatus` is set to `BLOCKED`
**And** no SSE event is emitted to the recipient — the blocked message is never delivered
**And** the sender receives an SSE event `{ type: "MESSAGE_BLOCKED", messageId, conversationId }` so their UI can show a delivery failure indicator — the sender is informed their message was not delivered but NOT told the specific reason
**And** no `ErrorDto` is returned on the original POST — the POST returned `202 Accepted` when the message was received; moderation outcome is communicated asynchronously via SSE

**Given** Gemini returns an UNCERTAIN verdict (content flagged for human review)
**When** the moderation result is processed
**Then** `messages.moderationStatus` is set to `UNDER_REVIEW`
**And** no SSE event is emitted — the message is held pending admin action
**And** the message appears in the admin moderation queue (Epic 10)

**Given** the Gemini API call fails (network error, timeout, non-2xx response)
**When** the moderation service processes the result
**Then** the system is fail-closed: `messages.moderationStatus` is set to `UNDER_REVIEW` — failed moderation holds the message, never delivers it
**And** a `ModerationFailureEvent` is published for observability (logged at WARN level with `messageId`, `conversationId`, `failureReason`)
**And** NO retry is attempted inline — held messages are reviewed by admin in Epic 10 or cleared by a future background job (post-MVP)

**Given** message content exceeds the Gemini API input limit
**When** `GeminiModerationService.moderate()` is called
**Then** content is truncated to the configured `moderation.gemini.maxInputChars` limit (from application properties) before sending to Gemini — truncation is logged at DEBUG level with `messageId`
**And** the message content stored in `messages.content` is always the FULL original content — only the Gemini input is truncated

**Given** a `BLOCKED` or `UNDER_REVIEW` message
**When** `GET /api/messaging/conversations/{conversationId}/messages` is called
**Then** `BLOCKED` messages are returned with `content = null` and `moderationStatus = BLOCKED` — the content is not exposed to either party
**And** `UNDER_REVIEW` messages are returned with `content = null` and `moderationStatus = UNDER_REVIEW` — held pending admin review

*Dev notes: `platform.messaging`. `GeminiModerationService implements ModerationService` with `@Primary @Service`. `PassThroughModerationService` retains `@Service` (no `@Primary`) — injected explicitly in `MessagingServiceTest` via `@Qualifier`. Gemini call in `GeminiModerationService.callGemini()` — plain method, no `@Transactional`. DB write in `ModerationResultApplier.applyResult(messageId, verdict)` — `@Transactional`. SSE emission after DB write, outside both. Gemini response parsed to one of three verdicts: SAFE / UNSAFE / UNCERTAIN — model response schema defined in `GeminiModerationResponse` DTO. `moderation.gemini.promptTemplate` and `moderation.gemini.maxInputChars` in application properties. Test: `GeminiModerationServiceTest` (unit — mock Gemini client: safe, unsafe, uncertain, API failure → all set correct status), `ModerationFailClosedIT` (integration — Gemini unavailable → UNDER_REVIEW, no SSE to recipient), `BlockedMessageContentHidingIT` (blocked message returns null content in list endpoint).*

---

### Story 8.4: Abuse Reporting & Message Retention Scheduler

As a platform user,
I want to report abusive messages and as a platform operator I want message data automatically hard-deleted after 24 months,
So that users have a safe reporting mechanism and the platform meets GDPR data minimisation obligations.

**Acceptance Criteria:**

**Given** a user wants to report a message
**When** `POST /api/messaging/conversations/{conversationId}/messages/{messageId}/report` is called with `{ "reason": "HARASSMENT" | "INAPPROPRIATE_CONTENT" | "SPAM" | "CONTACT_SOLICITATION" | "OTHER" }`
**Then** `@PreAuthorize` verifies the authenticated user is a party to the conversation — `403` otherwise
**And** a row is inserted into `message_reports` (reportId UUID PK, messageId UUID NOT NULL FK, reportedBy UUID NOT NULL, reason ENUM NOT NULL, details VARCHAR(500) nullable, status ENUM(OPEN/UNDER_REVIEW/RESOLVED/DISMISSED) NOT NULL DEFAULT 'OPEN', createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable, resolvedBy UUID nullable)
**And** `messages.moderationStatus` is set to `UNDER_REVIEW` if not already `BLOCKED` — the reported message is immediately held pending admin review
**And** a `MessageReportedEvent(reportId, messageId, conversationId, reportedBy, reason)` is published for admin notification (Epic 10 wires the admin alert)
**And** `201 Created` is returned with the `reportId`
**And** a user may report the same message only once — duplicate report returns `409` with `ErrorDto` code `messaging.alreadyReported`

**Given** a user reports a conversation
**When** `POST /api/messaging/conversations/{conversationId}/report` is called with `{ "reason": "...", "details": "..." }`
**Then** same party verification applies
**And** a `conversation_reports` row is inserted (reportId, conversationId, reportedBy, reason, details, status, createdAt, resolvedAt, resolvedBy)
**And** `conversations.status` is set to `BLOCKED` — both parties can no longer send new messages
**And** a `ConversationReportedEvent` is published
**And** `201 Created` with `reportId`

**Given** a conversation is `BLOCKED`
**When** either party attempts to send a new message
**Then** `403` with `ErrorDto` code `messaging.conversationBlocked`
**And** both parties can still READ historical messages — they cannot send

**Given** the retention scheduler runs
**When** `@Scheduled(cron = "0 0 2 * * *")` fires daily at 02:00 UTC
**Then** `MessageRetentionScheduler` hard-deletes (SQL DELETE, not soft-delete) all rows from `messages` where `createdAt < now() - INTERVAL '24 months'`
**And** the corresponding `message_reports` rows for those messages are also deleted (cascade)
**And** after message deletion, any `conversations` where `lastMessageAt < now() - INTERVAL '24 months'` AND `messages` count = 0 are also hard-deleted along with their `conversation_reports` rows
**And** the count of deleted messages and conversations is logged at INFO level: `Retention run complete: deleted {messageCount} messages, {conversationCount} conversations`
**And** the 24-month retention window is read from ConfigService (`messaging.retentionMonths`, default 24) — never hardcoded

**Given** the retention scheduler encounters a DB error mid-run
**When** the exception propagates
**Then** the scheduler catches the exception, logs it at ERROR level with full stack trace, and does NOT rethrow — the next scheduled run picks up remaining rows

**Given** a sender wants to soft-delete their own message
**When** `DELETE /api/messaging/conversations/{conversationId}/messages/{messageId}` is called
**Then** `messages.deletedAt = now()` is set — the message is excluded from GET results for all users
**And** the content is NOT physically removed at this point — the retention scheduler handles physical deletion at 24 months
**And** only the original sender may soft-delete their own message — `403` on mismatch
**And** already-deleted messages return `409` with `ErrorDto` code `messaging.alreadyDeleted`

*Dev notes: `platform.messaging`. New tables: `message_reports`, `conversation_reports` — Flyway migration. `MessageRetentionScheduler` annotated `@Component`; deletion method annotated `@Transactional`. `MessageReportedEvent` and `ConversationReportedEvent` published via Spring `ApplicationEventPublisher` — consumed by admin module in Epic 10. Test: `AbuseReportIT` (submit report → UNDER_REVIEW, duplicate → 409, blocked conversation → no new sends), `SoftDeleteIT` (sender can delete, non-sender 403, excluded from list), `RetentionSchedulerTest` (unit — mock clock: messages older than 24 months deleted, recent messages retained, empty conversation cleanup, DB error → no rethrow).*

---

## Epic 9: Reviews & Ratings

### Story 9.1: Review Submission & Eligibility

As a parent or player,
I want to leave a star rating and optional written review for a coach I have trained with,
So that other parents and players can make informed decisions when choosing a coach.

**Acceptance Criteria:**

**Given** the `platform.reviews` module is initialised
**When** the Flyway migration runs
**Then** the following table exists:
`coach_reviews` (reviewId UUID PK, coachId UUID NOT NULL, authorId UUID NOT NULL, authorRole ENUM(PARENT/PLAYER) NOT NULL, rating SMALLINT NOT NULL CHECK(rating BETWEEN 1 AND 5), body VARCHAR(1000) nullable, moderationStatus ENUM(PENDING/APPROVED/BLOCKED/UNDER_REVIEW) NOT NULL DEFAULT 'PENDING', coachResponseBody VARCHAR(500) nullable, coachResponseAt TIMESTAMPTZ nullable, createdAt TIMESTAMPTZ, lastModifiedAt TIMESTAMPTZ NOT NULL)
**And** a unique constraint exists on `coach_reviews(authorId, coachId)` — one review per author per coach, regardless of session count

**Given** a parent or player wants to submit a review for a coach
**When** `POST /api/reviews/coaches/{coachId}` is called with `{ "rating": 4, "body": "..." }`
**Then** a recency gate is checked: `EXISTS (SELECT 1 FROM bookings WHERE (parentId = authenticatedUser.id OR playerId = authenticatedUser.id) AND coachId = {coachId} AND status = COMPLETED AND completedAt >= now() - INTERVAL '{reviews.submissionWindowDays} days')` — the window is read from ConfigService (`reviews.submissionWindowDays`, default 14); `403` with `ErrorDto` code `reviews.noRecentSession` if the check fails
**And** if a review already exists for this `(authorId, coachId)` pair: `409` with `ErrorDto` code `reviews.alreadySubmitted` — the author should use PATCH to update
**And** the review is created with `moderationStatus = PENDING` and `lastModifiedAt = now()`
**And** `ReviewSubmittedEvent(reviewId, coachId, authorId, rating, body)` is published — Story 9.2 handles moderation
**And** `201 Created` with the `reviewId` is returned

**Given** a review body is provided
**When** the submission is validated
**Then** `body` must not exceed 1000 characters — `400` with `ErrorDto` code `reviews.bodyTooLong`
**And** `body` may be null or empty — a rating-only review is valid

**Given** an author wants to update their existing review
**When** `PATCH /api/reviews/{reviewId}` is called with `{ "rating": 5, "body": "updated text" }`
**Then** `@PreAuthorize` verifies `coach_reviews.authorId == authenticatedUser.id` — `403` on mismatch
**And** the recency gate is re-checked: a completed session with this coach within the last `reviews.submissionWindowDays` days must exist — `403` with `ErrorDto` code `reviews.noRecentSession` if not; the author must be actively training with the coach to update their review
**And** an annual gate is checked: `coach_reviews.lastModifiedAt < now() - INTERVAL '365 days'` — `403` with `ErrorDto` code `reviews.updateTooSoon` if the review was created or last updated less than 365 days ago; updates are permitted at most once per year
**And** edits are only permitted while `moderationStatus IN (APPROVED, PENDING)` — `403` with `ErrorDto` code `reviews.editNotPermitted` if `BLOCKED` or `UNDER_REVIEW`
**And** `moderationStatus` is reset to `PENDING`, `lastModifiedAt = now()`, and a new `ReviewSubmittedEvent` is published — the review re-enters the moderation queue
**And** `coachResponseBody` and `coachResponseAt` are cleared — the coach may submit a fresh response to the updated review

**Given** a coach wants to respond to an approved review
**When** `POST /api/reviews/{reviewId}/response` is called with `{ "body": "..." }`
**Then** `@PreAuthorize` verifies `coach_reviews.coachId == authenticatedCoach.id` — `403` on mismatch
**And** a response is only permitted if `moderationStatus = APPROVED` — `403` with `ErrorDto` code `reviews.reviewNotApproved`
**And** if `coachResponseBody IS NOT NULL`: `409` with `ErrorDto` code `reviews.responseAlreadySubmitted` — the coach gets one response per review version; if the author updates their review, the response is cleared and the coach may respond again
**And** `coachResponseBody` must not exceed 500 characters — `400` with `ErrorDto` code `reviews.responseTooLong`
**And** `coachResponseBody` and `coachResponseAt` are set; `moderationStatus` is unchanged — the response is stored but not separately moderated for MVP

*Dev notes: `platform.reviews`. `ReviewResource`: POST /api/reviews/coaches/{coachId}, PATCH /api/reviews/{reviewId}, POST /api/reviews/{reviewId}/response. Eligibility check: cross-module query via `BookingRepository` using `existsByParentIdOrPlayerIdAndCoachIdAndStatusAndCompletedAtAfter()` — reviews depends on booking contract only. `reviews.submissionWindowDays` and the 365-day annual gate constant both read from ConfigService at call time. `ReviewSubmittedEvent` published via Spring `ApplicationEventPublisher` — consumed by `ReviewModerationService` in Story 9.2. Test: `ReviewSubmissionIT` (no recent session → 403, success → 201, duplicate → 409), `ReviewUpdateIT` (recency gate, annual gate, BLOCKED edit 403, response cleared on update), `CoachResponseIT` (one response per version, non-approved 403, duplicate 409).*

---

### Story 9.2: Review Moderation & Rating Aggregation

As a platform operator,
I want reviews moderated before they appear on coach profiles and coach ratings computed from approved reviews only,
So that the displayed rating is trustworthy and harmful content never reaches the public profile.

**Acceptance Criteria:**

**Given** a `ReviewSubmittedEvent` is received
**When** `ReviewModerationService.moderate(reviewId)` processes it
**Then** `GeminiModerationService` is called with the review body (if present) OUTSIDE a `@Transactional` boundary — same pattern as Story 8.3
**And** if body is null or empty (rating-only review): moderation is skipped and `moderationStatus` is set directly to `APPROVED`
**And** after the Gemini call, a `@Transactional` write updates `coach_reviews.moderationStatus`
**And** on SAFE verdict: `moderationStatus = APPROVED`; coach rating aggregate is recomputed
**And** on UNSAFE verdict: `moderationStatus = BLOCKED`; review never appears on profile; coach rating unchanged
**And** on UNCERTAIN verdict or Gemini API failure: `moderationStatus = UNDER_REVIEW`; review held for admin queue (Epic 10); coach rating unchanged — fail-closed

**Given** one or more APPROVED reviews exist for a coach
**When** the coach's rating aggregate is recomputed (triggered by any review transitioning to or from APPROVED)
**Then** `CoachRatingService.recompute(coachId)` updates `coach_profiles.averageRating = ROUND(AVG(rating), 1)` and `coach_profiles.reviewCount = COUNT(*)` using only `moderationStatus = APPROVED` reviews
**And** both fields are updated in a single `@Transactional` write on `coach_profiles`
**And** if no APPROVED reviews exist: `averageRating = null` and `reviewCount = 0`

**Given** an admin resolves a review from `UNDER_REVIEW` to `APPROVED` or `BLOCKED` (Epic 10 action)
**When** the status transition is persisted
**Then** a `ReviewModerationResolvedEvent(reviewId, coachId, newStatus)` is published
**And** `CoachRatingService.recompute(coachId)` is called if `newStatus = APPROVED` or the review was previously APPROVED and is now BLOCKED

**Given** a user views a coach's public profile
**When** `GET /api/coaches/{coachId}/profile` includes rating data
**Then** `averageRating` (nullable, 1 decimal place) and `reviewCount` are returned from `coach_profiles` — pre-computed values, not computed at query time

**Given** a user wants to read coach reviews
**When** `GET /api/coaches/{coachId}/reviews?page={n}&sort=newest|highest|lowest` is called
**Then** only `moderationStatus = APPROVED` reviews are returned
**And** each `ReviewDto` contains: `reviewId`, `authorRole`, `rating`, `body`, `coachResponseBody` (nullable), `coachResponseAt` (nullable), `createdAt`, `lastModifiedAt`
**And** `authorId` is NOT returned — reviews are anonymous to the public; only `authorRole` (PARENT/PLAYER) is shown
**And** pagination: 10 reviews per page; default sort is `newest` (`lastModifiedAt DESC`)

**Given** a coach wants to see all their reviews including pending and blocked
**When** `GET /api/coaches/me/reviews` is called by an authenticated coach
**Then** all reviews for `coach_reviews.coachId = authenticatedCoach.id` are returned regardless of `moderationStatus`
**And** `authorId` is NOT returned — coaches do not see who wrote which review
**And** `moderationStatus` IS returned so the coach knows which reviews are visible to the public

*Dev notes: `platform.reviews`. `ReviewModerationService` consumes `ReviewSubmittedEvent` via `@EventListener` (synchronous for MVP). `CoachRatingService.recompute()` issues `UPDATE coach_profiles SET averageRating = (...), reviewCount = (...) WHERE coachId = ?` — cross-module write; new columns added via Flyway migration in this story: `averageRating NUMERIC(3,1)`, `reviewCount INT NOT NULL DEFAULT 0`. Re-aggregation on every approval is acceptable for MVP given low review volume. Test: `ReviewModerationIT` (safe → APPROVED + rating recomputed; unsafe → BLOCKED; null body → APPROVED without Gemini call; Gemini failure → UNDER_REVIEW), `RatingAggregationTest` (unit — various APPROVED/BLOCKED combinations, null when no approved reviews), `PublicReviewListIT` (only APPROVED surfaced, authorId absent, coach sees own pending reviews).*

---

### Story 9.3: Review Visibility, Flagging & Admin Resolution

As a parent or player,
I want to flag suspicious reviews and as a platform admin I want to be able to review flagged content and reinstate or remove reviews,
So that the review system remains trustworthy and coaches cannot be harmed by coordinated fake negative reviews.

**Acceptance Criteria:**

**Given** a user views coach search results
**When** `GET /api/coaches/search` returns coach cards
**Then** each coach card includes `averageRating` (nullable) and `reviewCount` from `coach_profiles` — the pre-computed fields from Story 9.2; no additional query per coach is made

**Given** a user views a coach's full profile
**When** `GET /api/coaches/{coachId}/profile` is called
**Then** the response includes the rating summary (`averageRating`, `reviewCount`) and the first page of APPROVED reviews inline — same data as `GET /api/coaches/{coachId}/reviews?page=0&sort=newest`; one round-trip for the full profile view

**Given** a user wants to flag a review as fake or abusive
**When** `POST /api/reviews/{reviewId}/flag` is called with `{ "reason": "FAKE_REVIEW" | "OFFENSIVE_CONTENT" | "CONFLICT_OF_INTEREST" | "OTHER", "details": "..." }`
**Then** `@PreAuthorize` authenticated user required; the flagger must not be the review author — `403` with `ErrorDto` code `reviews.cannotFlagOwnReview`
**And** a row is inserted into `review_flags` (flagId UUID PK, reviewId UUID NOT NULL FK, flaggedBy UUID NOT NULL, reason ENUM NOT NULL, details VARCHAR(500) nullable, createdAt TIMESTAMPTZ)
**And** if `review_flags` count for this `reviewId` reaches `reviews.autoHoldFlagThreshold` (from ConfigService, default 3): `coach_reviews.moderationStatus` is set to `UNDER_REVIEW` and `CoachRatingService.recompute(coachId)` is called — the review is pulled from public view pending admin decision
**And** a `ReviewFlaggedEvent(reviewId, coachId, flagCount, autoHeld)` is published for admin notification
**And** a user may flag the same review only once — `409` with `ErrorDto` code `reviews.alreadyFlagged`
**And** `201 Created` with the `flagId`

**Given** an admin views the review moderation queue
**When** `GET /api/admin/reviews/queue?status=UNDER_REVIEW&page={n}` is called by an authenticated admin
**Then** all reviews with `moderationStatus = UNDER_REVIEW` are returned, ordered by `updatedAt ASC` (oldest held first)
**And** each entry includes: `reviewId`, `coachId`, `coachName`, `authorRole`, `rating`, `body`, `createdAt`, `lastModifiedAt`, `heldReason` (GEMINI_UNCERTAIN / GEMINI_FAILURE / FLAG_THRESHOLD / MANUAL), `flagCount` (count of rows in `review_flags` for this review), `flags` (list of `{ reason, details, createdAt }` — `flaggedBy` is NOT included; admin sees flag content but not the flaggers' identities)

**Given** an admin reviews a held review
**When** `POST /api/admin/reviews/{reviewId}/approve` is called
**Then** `@PreAuthorize` admin role required
**And** `coach_reviews.moderationStatus` is set to `APPROVED`
**And** all `review_flags` rows for this review are soft-resolved: a `resolvedAt = now()` column is set on each flag row (flags are retained for audit; not deleted)
**And** `CoachRatingService.recompute(coachId)` is called — the review re-enters the aggregate
**And** a `ReviewModerationResolvedEvent(reviewId, coachId, APPROVED, adminId)` is published
**And** `200 OK`

**Given** an admin determines a review violates platform rules
**When** `POST /api/admin/reviews/{reviewId}/block` is called with `{ "reason": "..." }`
**Then** `@PreAuthorize` admin role required
**And** `coach_reviews.moderationStatus` is set to `BLOCKED`
**And** all `review_flags` rows for this review are soft-resolved (`resolvedAt = now()`)
**And** `CoachRatingService.recompute(coachId)` is called if the review was previously APPROVED
**And** a `ReviewModerationResolvedEvent(reviewId, coachId, BLOCKED, adminId)` is published
**And** `200 OK`

**Given** a review has been blocked by admin
**When** the review author calls `GET /api/reviews/me/coaches/{coachId}`
**Then** their own review is returned with `moderationStatus = BLOCKED` — the author can see their review was removed but is not told who made the decision or why

**Given** an authenticated author views their own review
**When** `GET /api/reviews/me/coaches/{coachId}` is called
**Then** the author's own review for that coach is returned including `moderationStatus`
**And** `403` if no review exists for this `(authorId, coachId)` pair — not `404`

**Given** a coach's account is suspended or deleted
**When** the coach's profile is deactivated
**Then** `coach_profiles.averageRating` and `reviewCount` are retained in the DB — reviews are historical records and are not deleted on deactivation; Epic 10 handles profile visibility for deactivated coaches

*Dev notes: `platform.reviews`. New tables: `review_flags` (unique on `(reviewId, flaggedBy)`; `resolvedAt TIMESTAMPTZ nullable` for soft-resolution), `review_moderation_log` (reviewId, adminId, action ENUM(APPROVED/BLOCKED), reason VARCHAR, createdAt) — audit trail of admin decisions. `heldReason` column added to `coach_reviews`: ENUM(GEMINI_UNCERTAIN/GEMINI_FAILURE/FLAG_THRESHOLD/MANUAL) NOT NULL — set at the time the review is moved to UNDER_REVIEW. Admin endpoints in `platform.admin` module: GET /api/admin/reviews/queue, POST /api/admin/reviews/{id}/approve, POST /api/admin/reviews/{id}/block — all `@PreAuthorize` admin role. `CoachRatingService.recompute()` called on both approve and block paths. Profile endpoint enrichment: `CoachProfileResource` calls `ReviewQueryService.getFirstPageForCoach(coachId)` — cross-module read. Test: `ReviewFlagIT` (flag threshold → UNDER_REVIEW + recompute, own-review flag 403, duplicate 409), `AdminReviewQueueIT` (queue contains UNDER_REVIEW reviews with flag details, no flaggedBy identity), `AdminApproveIT` (approve → APPROVED + recompute + flags resolved + event), `AdminBlockIT` (block → BLOCKED + recompute if was APPROVED + flags resolved), `AuthorSelfViewIT` (author sees own moderationStatus including BLOCKED, 403 when none exists).*

---

## Epic 10: Admin & Platform Safety

### Story 10.1: Admin Moderation Queue & Message Content Actions

As a platform admin,
I want a unified queue showing all held messages and conversation reports, and the ability to approve or block individual messages,
So that harmful content flagged by Gemini or reported by users is resolved promptly without manual triage across separate systems.

**Acceptance Criteria:**

**Given** a `MessageReportedEvent` or `ConversationReportedEvent` is published (Story 8.4)
**When** the admin module consumes it
**Then** an `admin_alerts` row is inserted: (alertId UUID PK, type ENUM(MESSAGE_REPORT/CONVERSATION_REPORT/REVIEW_FLAG/STRIKE_THRESHOLD/DISPUTE_RAISED) NOT NULL, referenceId UUID NOT NULL, referenceType ENUM(MESSAGE/CONVERSATION/REVIEW/COACH/BOOKING) NOT NULL, status ENUM(OPEN/IN_PROGRESS/RESOLVED) NOT NULL DEFAULT 'OPEN', createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable, resolvedBy UUID nullable)
**And** similarly, a `ReviewFlaggedEvent` (Story 9.3) and a `StrikeThresholdReachedEvent` (Story 7.3) each insert an `admin_alerts` row with the appropriate type and referenceId

**Given** an admin views the moderation queue
**When** `GET /api/admin/queue?type={MESSAGE_REPORT|CONVERSATION_REPORT|REVIEW_FLAG|ALL}&status=OPEN&page={n}` is called
**Then** `@PreAuthorize` admin role required
**And** alerts are returned ordered by `createdAt ASC` — oldest unresolved first
**And** each entry includes: `alertId`, `type`, `referenceId`, `referenceType`, `status`, `createdAt`, and a `summary` field — for MESSAGE_REPORT: first 100 chars of message content; for CONVERSATION_REPORT: reporter's stated reason; for REVIEW_FLAG: `flagCount` and top flag reason

**Given** an admin views a held message (moderationStatus = UNDER_REVIEW)
**When** `GET /api/admin/messages/{messageId}` is called
**Then** the full message content is returned including `senderId`, `senderRole`, `conversationId`, `moderationStatus`, `createdAt`
**And** the conversation context is included: last 5 messages before and after this message (ordered by createdAt) so the admin can assess context — blocked messages in the context window show `content = null`
**And** all `message_reports` for this message are returned with `reason` and `details` — `reportedBy` identity is NOT included

**Given** an admin approves a held message
**When** `POST /api/admin/messages/{messageId}/approve` is called
**Then** `messages.moderationStatus` is set to `APPROVED` and `messages.deliveredAt = now()`
**And** an SSE event `{ type: "NEW_MESSAGE", messageId, conversationId }` is emitted to the recipient's active connection
**And** all `message_reports` for this message are soft-resolved (`resolvedAt = now()`)
**And** the corresponding `admin_alerts` row is resolved (`status = RESOLVED`, `resolvedAt = now()`, `resolvedBy = adminId`)
**And** `200 OK`

**Given** an admin blocks a message
**When** `POST /api/admin/messages/{messageId}/block` is called with `{ "reason": "..." }`
**Then** `messages.moderationStatus` is set to `BLOCKED`
**And** an SSE event `{ type: "MESSAGE_BLOCKED", messageId, conversationId }` is emitted to the sender's active connection
**And** all `message_reports` soft-resolved; `admin_alerts` row resolved
**And** the action is logged in `admin_action_log` (actionId UUID PK, adminId UUID NOT NULL, actionType ENUM(MESSAGE_APPROVE/MESSAGE_BLOCK/CONVERSATION_UNBLOCK/REVIEW_APPROVE/REVIEW_BLOCK/COACH_SUSPEND/COACH_REINSTATE/DISPUTE_RESOLVE) NOT NULL, referenceId UUID NOT NULL, reason VARCHAR(500) nullable, createdAt TIMESTAMPTZ)
**And** `200 OK`

**Given** an admin views a reported conversation
**When** `GET /api/admin/conversations/{conversationId}` is called
**Then** the full conversation thread is returned with all messages regardless of `moderationStatus`, ordered by `createdAt ASC`
**And** `conversation_reports` for this conversation are included with `reason` and `details` — `reportedBy` identity NOT included
**And** `conversations.status` is included so the admin can see if it was auto-blocked on report

**Given** an admin wants to unblock a conversation that was incorrectly reported
**When** `POST /api/admin/conversations/{conversationId}/unblock` is called
**Then** `conversations.status` is set to `ACTIVE` — both parties can send messages again
**And** the `conversation_reports` row is soft-resolved
**And** the `admin_alerts` row is resolved
**And** action logged in `admin_action_log`
**And** `200 OK`

**Given** an admin wants to see a summary of unresolved work
**When** `GET /api/admin/queue/summary` is called
**Then** a count of OPEN alerts is returned broken down by type: `{ messageReports: N, conversationReports: N, reviewFlags: N, strikeAlerts: N, disputes: N, total: N }`
**And** this endpoint is `@Transactional(readOnly=true)` and returns in a single query via GROUP BY

*Dev notes: `platform.admin`. New tables: `admin_alerts`, `admin_action_log` — Flyway migration. `admin_alerts` is the single fan-in point for all cross-module safety events; each module publishes domain events, admin module consumes via `@EventListener`. Admin endpoints: GET /api/admin/queue, GET /api/admin/queue/summary, GET /api/admin/messages/{id}, POST /api/admin/messages/{id}/approve, POST /api/admin/messages/{id}/block, GET /api/admin/conversations/{id}, POST /api/admin/conversations/{id}/unblock — all `@PreAuthorize` admin role. Review admin endpoints (approve/block) defined in Story 9.3 also live in this module. Cross-module reads: `MessageQueryService` and `ConversationQueryService` interfaces exposed by `platform.messaging`; admin module calls these, never queries messaging tables directly. Test: `AdminQueueIT` (alert inserted on event, queue returns correct items, summary counts), `MessageApproveIT` (approve → APPROVED + SSE + reports resolved + alert resolved), `MessageBlockIT` (block → BLOCKED + sender SSE + logged), `ConversationUnblockIT` (unblock → ACTIVE + reports resolved).*

---

### Story 10.2: Coach Enforcement & Strike Management

As a platform admin,
I want to view coach reliability strikes, manually intervene when needed, and suspend coaches who breach thresholds,
So that the platform remains safe for players and parents and coaches are treated fairly through a transparent process.

**Acceptance Criteria:**

**Given** a `StrikeThresholdReachedEvent` is published (Story 7.3)
**When** the admin module consumes it
**Then** an `admin_alerts` row is inserted (type = STRIKE_THRESHOLD) — already defined in Story 10.1
**And** the coach's status in `coach_profiles` is set to `PENDING_REVIEW` — the coach can still operate but is flagged for admin attention; no automatic suspension without admin action

**Given** an admin views a coach's enforcement profile
**When** `GET /api/admin/coaches/{coachId}/enforcement` is called
**Then** `@PreAuthorize` admin role required
**And** the response includes:
- `coachId`, `coachName`, `currentStatus` (ACTIVE/PENDING_REVIEW/SUSPENDED/DEACTIVATED)
- `activeStrikes`: count of strikes in the rolling 30-day window from `coach_reliability_strikes`
- `strikeHistory`: all rows from `coach_reliability_strikes` ordered by `issuedAt DESC`, each with `reason`, `bookingId`, `issuedAt`, `acknowledged`
- `cancellationHistory`: all rows from `coach_cancellation_history` ordered by `cancelledAt DESC`
- `openAlerts`: count of OPEN `admin_alerts` referencing this coach

**Given** an admin decides to suspend a coach
**When** `POST /api/admin/coaches/{coachId}/suspend` is called with `{ "reason": "...", "notifyCoach": true }`
**Then** `coach_profiles.status` is set to `SUSPENDED`
**And** all future bookings in `REQUESTED` state for this coach are cancelled — `BookingCancelledByAdminEvent` published for each, credit refunded to parent (100%) via the standard refund path
**And** all bookings in `ACCEPTED` state remain in place — already-confirmed sessions are not auto-cancelled; admin must manually cancel individual upcoming sessions if needed
**And** if `notifyCoach = true`: a `CoachSuspensionNotificationEvent` is published (notification delivery out of scope for this story)
**And** `CoachSuspendedEvent(coachId, reason, adminId)` is published
**And** action logged in `admin_action_log` (actionType = COACH_SUSPEND)
**And** `200 OK`

**Given** a suspended coach's profile is viewed by a parent or player
**When** `GET /api/coaches/{coachId}/profile` is called
**Then** `404` is returned — suspended coach profiles are not publicly visible; existing bookings with the coach are unaffected and accessible via the booking module

**Given** an admin reinstates a suspended coach
**When** `POST /api/admin/coaches/{coachId}/reinstate` is called with `{ "reason": "..." }`
**Then** `coach_profiles.status` is set to `ACTIVE`
**And** the corresponding `admin_alerts` row (type = STRIKE_THRESHOLD) is resolved
**And** `CoachReinstatedEvent(coachId, adminId)` is published
**And** action logged in `admin_action_log` (actionType = COACH_REINSTATE)
**And** `200 OK`

**Given** an admin wants to manually issue a strike outside the automated system
**When** `POST /api/admin/coaches/{coachId}/strikes` is called with `{ "bookingId": "...", "reason": "COACH_CANCELLATION_UNEXCUSED" | "COACH_NO_SHOW" }`
**Then** a new row is inserted into `coach_reliability_strikes` with `issuedAt = now()`
**And** the strike threshold check is re-evaluated — if the new strike tips the coach over `suspensionThreshold`: `StrikeThresholdReachedEvent` is published and a new `admin_alerts` row created
**And** action logged in `admin_action_log`
**And** `201 Created` with the `strikeId`

**Given** an admin wants to remove a strike that was issued in error
**When** `DELETE /api/admin/coaches/{coachId}/strikes/{strikeId}` is called with `{ "reason": "..." }`
**Then** the strike row is hard-deleted from `coach_reliability_strikes`
**And** the threshold check is re-evaluated — if the coach is now below `visibilityThreshold`, `coach_profiles.status` is set back to `ACTIVE` if currently `PENDING_REVIEW`
**And** action logged in `admin_action_log`
**And** `200 OK`

**Given** an admin views the list of all coaches in PENDING_REVIEW or SUSPENDED status
**When** `GET /api/admin/coaches?status=PENDING_REVIEW|SUSPENDED&page={n}` is called
**Then** a paginated list is returned with `coachId`, `coachName`, `status`, `activeStrikes`, `statusChangedAt`, ordered by `statusChangedAt ASC` — oldest unresolved first

**Given** a parent attempts to request a booking with a suspended coach
**When** `POST /api/booking` is called and `coach_profiles.status = SUSPENDED`
**Then** `403` with `ErrorDto` code `booking.coachUnavailable` — the booking guard in `BookingRequestService` (Story 3.3) must check `coach_profiles.status IN (ACTIVE)` before creating the booking record; this check is added when implementing Story 10.2

*Dev notes: `platform.admin`. Coach status (`ACTIVE/PENDING_REVIEW/SUSPENDED/DEACTIVATED`) added to `coach_profiles` via Flyway migration in this story: `status ENUM(...) NOT NULL DEFAULT 'ACTIVE'`. Suspension auto-cancellation of REQUESTED bookings: admin module publishes `BookingCancelledByAdminEvent` per booking — booking module consumes and processes refund via standard credit path. `BookingRequestService.requestBooking()` (Story 3.3) gains a `coach_profiles.status = ACTIVE` pre-check as part of this story's implementation — not a separate story. Cross-module reads: `CoachEnforcementQueryService` interface exposed by `platform.coach`; admin calls this, never queries coach tables directly. Test: `CoachSuspensionIT` (suspend → SUSPENDED + REQUESTED bookings cancelled + profile 404), `SuspendedCoachBookingBlockIT` (booking request against SUSPENDED coach → 403), `ReinstateIT` (reinstate → ACTIVE + alert resolved), `ManualStrikeIT` (add strike → threshold check, delete strike → threshold re-evaluated), `CoachEnforcementListIT` (filter by status, ordered by statusChangedAt).*

---

### Story 10.3: Dispute Resolution

As a parent or player,
I want to raise a formal dispute when a session did not go as expected and I believe I am owed a refund,
So that I have a fair resolution path beyond the standard cancellation policy.

**Acceptance Criteria:**

**Given** a parent or player wants to raise a dispute
**When** `POST /api/disputes` is called with `{ "bookingId": "...", "reason": "COACH_NO_SHOW" | "SESSION_QUALITY" | "SAFETY_CONCERN" | "UNAUTHORISED_CHARGE" | "OTHER", "details": "..." }`
**Then** eligibility is checked: `bookings.status IN (COMPLETED, CANCELLED)` AND `(bookings.parentId = authenticatedUser.id OR bookings.playerId = authenticatedUser.id)` — `403` with `ErrorDto` code `disputes.notEligible` if not met
**And** a dispute can only be raised within `disputes.submissionWindowDays` (ConfigService, default 14) days of `bookings.completedAt` or `bookings.cancelledAt` — `403` with `ErrorDto` code `disputes.windowExpired`
**And** only one open dispute per booking — `409` with `ErrorDto` code `disputes.alreadyRaised` if a dispute with `status NOT IN (RESOLVED, DISMISSED)` already exists for this booking
**And** a row is inserted into `disputes` (disputeId UUID PK, bookingId UUID NOT NULL, raisedBy UUID NOT NULL, raisedByRole ENUM(PARENT/PLAYER) NOT NULL, reason ENUM NOT NULL, details VARCHAR(2000) NOT NULL, status ENUM(OPEN/UNDER_REVIEW/RESOLVED/DISMISSED) NOT NULL DEFAULT 'OPEN', resolution ENUM(FULL_CREDIT/PARTIAL_CREDIT/NO_ACTION/COACH_WARNING) nullable, resolutionNote VARCHAR(1000) nullable, createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable, resolvedBy UUID nullable)
**And** a `DisputeRaisedEvent(disputeId, bookingId, raisedBy, reason)` is published — admin module inserts an `admin_alerts` row (type = DISPUTE_RAISED)
**And** `201 Created` with the `disputeId`

**Given** a parent or player views their dispute
**When** `GET /api/disputes/{disputeId}` is called
**Then** `@PreAuthorize` verifies `disputes.raisedBy == authenticatedUser.id` — `403` on mismatch
**And** current `status`, `resolution` (nullable), and `resolutionNote` (nullable) are returned — the user can track progress but cannot see internal admin notes

**Given** an admin reviews a dispute
**When** `GET /api/admin/disputes/{disputeId}` is called
**Then** the full dispute record is returned including booking details: `coachId`, `coachName`, `sessionDate`, `sessionPrice`, `paymentRecord` (creditDebited, stripeCharged from `booking_payments`), and the dispute `details`
**And** the booking's cancellation history is included if the booking was cancelled

**Given** an admin resolves a dispute with a full credit refund
**When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "FULL_CREDIT", "resolutionNote": "..." }`
**Then** `disputes.status = RESOLVED`, `disputes.resolution = FULL_CREDIT`, `disputes.resolvedAt = now()`, `disputes.resolvedBy = adminId`
**And** the full session price is credited to `parent_credit_ledger` via a `BOOKING_REFUND` entry — same credit mechanism used by the cancellation flow in Story 7.3; no new payment code
**And** `admin_alerts` row resolved; action logged in `admin_action_log` (actionType = DISPUTE_RESOLVE)
**And** `DisputeResolvedEvent(disputeId, bookingId, resolution, raisedBy)` is published
**And** `200 OK`

**Given** an admin resolves a dispute with a partial credit
**When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "PARTIAL_CREDIT", "creditAmount": 30.00, "resolutionNote": "..." }`
**Then** `creditAmount` must be > 0 and ≤ the original session price — `400` with `ErrorDto` code `disputes.invalidCreditAmount` otherwise
**And** `creditAmount` is credited to `parent_credit_ledger` as a `BOOKING_REFUND` entry
**And** same status/alert/log/event flow as full credit

**Given** an admin finds no grounds for a refund
**When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "NO_ACTION", "resolutionNote": "..." }`
**Then** `disputes.status = RESOLVED`, `disputes.resolution = NO_ACTION`
**And** no credit or payment operation is performed
**And** same alert/log/event flow

**Given** an admin resolves a dispute with a coach warning
**When** `POST /api/admin/disputes/{disputeId}/resolve` is called with `{ "resolution": "COACH_WARNING", "resolutionNote": "...", "creditAmount": 0 | positive }`
**Then** a `CoachWarningIssuedEvent(coachId, disputeId, reason)` is published — the warning is recorded but does not automatically issue a strike; admin may separately issue a manual strike via Story 10.2 if warranted
**And** if `creditAmount > 0`: a `BOOKING_REFUND` ledger entry is created for the specified amount
**And** same status/alert/log/event flow

**Given** an admin dismisses a frivolous or duplicate dispute
**When** `POST /api/admin/disputes/{disputeId}/dismiss` is called with `{ "reason": "..." }`
**Then** `disputes.status = DISMISSED`
**And** no credit or payment operation is performed
**And** `admin_alerts` row resolved; action logged

*Dev notes: `platform.admin`. New table: `disputes` — Flyway migration. `DisputeRaisedEvent` consumed by admin module (`@EventListener`) → inserts `admin_alerts` row. Resolution credit path: `PaymentService.issueCreditRefund(parentId, amount, BOOKING_REFUND, bookingId)` — same method used by cancellation flow in Story 7.3; no new payment logic. `creditAmount` ceiling validated via cross-module read through `BookingPaymentQueryService`. Test: `DisputeSubmissionIT` (eligible → 201, ineligible → 403, window expired → 403, duplicate → 409), `AdminDisputeResolveIT` (full credit → ledger entry, partial credit → amount validation, no action → no ledger, coach warning → event published), `DisputeDismissIT`.*

---

### Story 10.4: GDPR Data Tools & Account Deletion

As a platform user,
I want to export all my personal data and request deletion of my account,
So that the platform meets my rights under GDPR (Articles 15, 17, and 20).

**Acceptance Criteria:**

**Given** a user submits a data export request
**When** `POST /api/gdpr/export` is called by an authenticated user
**Then** a row is inserted into `gdpr_requests` (requestId UUID PK, userId UUID NOT NULL, requestType ENUM(EXPORT/ERASURE) NOT NULL, status ENUM(PENDING/PROCESSING/COMPLETED/FAILED) NOT NULL DEFAULT 'PENDING', createdAt TIMESTAMPTZ, completedAt TIMESTAMPTZ nullable, downloadUrl VARCHAR nullable, expiresAt TIMESTAMPTZ nullable)
**And** `GdprExportRequestedEvent(requestId, userId)` is published — processing is asynchronous; the endpoint returns `202 Accepted` with the `requestId` immediately
**And** a user may have only one PENDING or PROCESSING export request at a time — `409` with `ErrorDto` code `gdpr.requestAlreadyPending`

**Given** a `GdprExportRequestedEvent` is consumed
**When** `GdprExportService.buildExport(userId)` runs
**Then** a ZIP archive is assembled containing JSON files for all personal data held about the user:
- `profile.json` — identity fields (name, email, DOB, phone)
- `bookings.json` — all bookings where user is coach, parent, or player
- `payments.json` — all `parent_credit_ledger` entries (parents only) and `booking_payments` records
- `messages.json` — all non-deleted messages sent by the user
- `reviews.json` — all reviews authored by the user
- `disputes.json` — all disputes raised by the user
**And** the ZIP is uploaded via the `filestorage` module and a pre-signed download URL generated with `gdpr.export.urlExpiryHours` (ConfigService, default 48) hour expiry
**And** `gdpr_requests.status = COMPLETED`, `downloadUrl` and `expiresAt` are set
**And** a `GdprExportReadyEvent(requestId, userId)` is published for notification delivery (out of scope)
**And** if the build fails: `gdpr_requests.status = FAILED`; the user may submit a new request

**Given** a user wants to download their export
**When** `GET /api/gdpr/export/{requestId}` is called
**Then** `@PreAuthorize` verifies `gdpr_requests.userId == authenticatedUser.id` — `403` on mismatch
**And** if `status = COMPLETED AND expiresAt > now()`: redirect `302` to the pre-signed `downloadUrl`
**And** if `status = COMPLETED AND expiresAt <= now()`: `410 Gone` with `ErrorDto` code `gdpr.exportExpired`
**And** if `status IN (PENDING, PROCESSING)`: `200` with `{ status, requestId }` — user can poll

**Given** a user submits an erasure request
**When** `POST /api/gdpr/erasure` is called by an authenticated user
**Then** `GdprErasureRequestedEvent(requestId, userId)` is published and `202 Accepted` returned
**And** a coach cannot submit an erasure request while they have any bookings in `REQUESTED` or `ACCEPTED` state — `409` with `ErrorDto` code `gdpr.activeBookingsExist`

**Given** a `GdprErasureRequestedEvent` is consumed
**When** `GdprErasureService.erase(userId)` runs
**Then** the following personal data fields are ANONYMISED (not deleted) to preserve financial and audit integrity:
- `users.email → 'deleted@platform.invalid'`, `users.firstName → 'Deleted'`, `users.lastName → 'User'`, `users.phone → null`, `users.dateOfBirth → null`, `users.avatarUrl → null`
- `coach_profiles.bio → null`, `coach_profiles.location → null`
**And** the following are HARD-DELETED:
- All `messages` authored by the user
- All `coach_reviews` authored by the user where `moderationStatus != APPROVED` — APPROVED reviews are anonymised (`authorId` set to platform anonymous UUID) to preserve coach rating integrity
- All Epic 5 player development records for the user: `skill_assessments`, `skill_snapshots`, `session_skill_logs`, `drill_completions` where `playerId = userId` — these are deleted in full as they are personal performance data with no legal retention obligation
- All `gdpr_requests` rows for this user older than 30 days
**And** the following are RETAINED unchanged (legal/financial obligation):
- `booking_payments`, `parent_credit_ledger` — financial records retained per German commercial law (HGB §257); `parentId` UUID is retained (non-PII after profile anonymisation)
- `admin_action_log`, `coach_reliability_strikes`, `coach_cancellation_history` — platform safety and audit records
- `disputes` — retained for legal dispute resolution
**And** `gdpr_requests.status = COMPLETED`, `completedAt = now()`
**And** the user's active session tokens are invalidated — `UserErasedEvent(userId)` published, consumed by `platform.identity` to revoke tokens
**And** `AccountDeletionRequestedEvent(userId)` is also published after profile anonymisation — consumed by `platform.video`'s existing `AccountDeletionCascadeListener` (Story 6.5) to trigger video deletion and the Bunny.net physical deletion outbox

**Given** an admin wants to track GDPR requests
**When** `GET /api/admin/gdpr/requests?type=EXPORT|ERASURE&status=PENDING&page={n}` is called
**Then** all requests are returned with `requestId`, `userId`, `requestType`, `status`, `createdAt`, `completedAt`
**And** no personal data beyond `userId` is returned

*Dev notes: `platform.admin`. New table: `gdpr_requests` — Flyway migration. Export ZIP assembled via `filestorage` module `StorageService` — same pre-signed URL pattern as filestorage Epic 2. Anonymous UUID for retained approved reviews: fixed well-known UUID stored in application properties as `gdpr.anonymousAuthorId`. Session invalidation via `UserErasedEvent(userId)` consumed by `platform.identity`. Video cascade via `AccountDeletionRequestedEvent` consumed by `platform.video`'s `AccountDeletionCascadeListener` (Story 6.5) — both events published in the same erasure transaction after. Player development data deletion: `GdprErasureService` deletes directly from Epic 5 tables via cross-module delete methods on `SkillAssessmentRepository`, `DrillCompletionRepository` etc. — same monolith, direct Spring injection. Financial retention period (`gdpr.financialRetentionYears`, default 10) from ConfigService. Test: `GdprExportIT` (request → 202, async build → COMPLETED + download URL, expired URL → 410), `GdprErasureIT` (PII fields nulled, messages deleted, development data deleted, APPROVED reviews anonymised, financial records retained, videos cascade triggered, session invalidated), `ActiveBookingsErasureBlockIT` (coach with active bookings → 409).*
