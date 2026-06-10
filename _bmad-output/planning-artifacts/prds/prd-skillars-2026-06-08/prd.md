---
title: Skillars — Product Requirements Document
status: final
created: 2026-06-08
updated: 2026-06-09
author: Mbah
---

# Skillars — Product Requirements Document

---

## 1. Vision & Problem Statement

**Product:** Skillars
**Type:** Two-sided marketplace + professional coaching platform
**MVP Geographic Scope:** Germany
**App Languages:** English, German

### 1.1 Problem

Private football coaching in Germany operates largely through informal channels (WhatsApp, Superprof, cash payment). This produces three structural failures:

1. **Administrative friction for coaches.** Independent coaches spend significant non-coaching time on scheduling, payment chasing, and session logging with no professional tooling.
2. **No measurable progress for families.** Parents have no objective evidence their child is developing. Coaching outcomes are invisible and unverifiable.
3. **Disjointed, unsafe communication.** Coach-parent-player communication happens across personal messaging apps with no audit trail, no parental oversight for minors, and no accountability mechanism.

### 1.2 Core Differentiation

Skillars is the only platform that treats **private football coaching as a professional service**. By focusing exclusively on the individual coach-player relationship — and deliberately excluding team management — Skillars provides the leanest and most capable tool in the market.

### 1.3 Value Proposition by Stakeholder

| Stakeholder | Core Value |
|---|---|
| **Coach** | A complete business operating system: scheduling, payment automation, session planning, and professional-grade reporting — tools previously only available to pro academies. |
| **Parent** | A permanent, portable development record for their child. Full visibility into sessions, communication, and measurable skill progress. |
| **Player** | A "Pro Academy" experience: structured feedback, the Locker Room (homework drills), and a Skills Radar that tracks long-term growth. |

### 1.4 Growth Model

Coach-led acquisition. Coaches self-register and build public profiles on the marketplace. Parents and players discover coaches through the marketplace and register to book.

### 1.5 Strategic Anchor

Player data is **owned by the player**, not the coach. Portability across coaches and longitudinal history create durable retention — the longer a player uses the platform, the more irreplaceable their development record becomes.

---

## 2. Users & Roles

### 2.1 Role Definitions

| Role | Description |
|---|---|
| **Guest** | Unauthenticated visitor. Can browse the marketplace and view coach profiles. Cannot contact coaches, request bookings, or leave reviews. |
| **Player** | Registered player account. Capabilities vary by age tier (§2.3). Managed by a parent when under 18. |
| **Parent** | Manages one or more player profiles under a single login (shadow accounts). Handles all payments, booking requests, and consent workflows on behalf of minor players. Maximum one parent account per player (enforced — see FR-USR-001). |
| **Coach** | Independent football coach. Self-registers, builds a public profile, sets pricing and availability, and manages their client roster. Operates as a solopreneur business on the platform. |
| **Admin** | Platform operator. Manages moderation queues, disputes, refunds, and enforcement actions. Accesses the platform via a protected admin role within the same Vue frontend. |

### 2.2 Coach Verification Tiers

Displayed as a badge on the coach's public profile and marketplace card. Granted by admin action only — not self-assigned.

| Level | Criteria |
|---|---|
| **Basic** | Email and phone number verified. |
| **Trusted** | Government-issued ID verified. |
| **Featured** | Platform-approved (manual editorial process). |

### 2.3 Player Age Tiers & Safeguards

Age is set at registration and verified by the parent account.

| Age Group | Account Type | Messaging | Video Uploads |
|---|---|---|---|
| **Under 10** | Parent-managed only. No independent player account. | None — all comms through parent. | Prohibited. |
| **10–12** | Parent-controlled player profile. | Parent-visible only; no direct player-to-coach messaging. | Prohibited. Requires parent approval before visible to player. |
| **13–17** | Player account permitted. | Player may message coaches; all messages mandatory-visible to parent. | Prohibited. Requires parent approval before visible to player. |
| **18+** | Full independent account. | Unrestricted within platform scope. | Unrestricted within quota limits. |

### 2.4 Account Architecture

- A single parent login manages multiple player profiles ("shadow accounts").
- All messages between players and coaches are visible to the parent at all times for all minor players.
- Timeline access: any coach with an active or previously paid training relationship with the player retains read access to that player's timeline. Access expires after a configurable inactivity period (stored in DB, not hardcoded — see FR-PLT-001). Access is also governed by parent permission settings.
- Players aged 18+ have no parental approval gate on coach-uploaded videos.

---

## 3. Platform Configuration Requirements

**FR-PLT-001 — Configurable Feature Gates**
All tier-based feature entitlements must be driven by a configuration layer persisted in the database, not hardcoded in application logic. The feature gate configuration must be modifiable by an admin without requiring a code deployment or application restart. This applies to: coach tier features (Scout / Instructor / Academy), player tier features (Athlete / Semi-Pro / Pro), all access-expiry windows (e.g., timeline access duration after relationship ends), and reliability strike thresholds.

**FR-PLT-002 — Configurable Platform Commission**
The platform commission rate (default 8%) must be stored in the configuration layer and modifiable by an admin without a code deployment. The commission rate is applied globally to all marketplace transactions at the value set in config at the time of each transaction.

---

## 4. UI Design System

**FR-UI-001 — UI Design System Constraint**
All frontend screens must conform to the Skillars Design System defined in `requirements/skillars/ui-design_v2.md`. This specification is mandatory input for every UI story. Key constraints:

- Framework: Quasar + Vue
- Aesthetic: Glassmorphism; dark mode is the default and primary aesthetic; light mode is a fully accessible alternative
- Mode switching: CSS custom properties only — never check `isDark` in JS to swap colours; use token variables exclusively
- Typography: Inter font family; weights 400, 500, 600, 700, 800
- No hardcoded hex values in any component — all colours via CSS custom property tokens (e.g., `var(--accent-primary)`)
- Every screen must be validated in both dark and light mode before sign-off
- WCAG AA contrast compliance required in both modes
- Touch targets minimum 44px; one-handed operation supported on mobile

---

## 5. Features & Functional Requirements

### 5.1 Marketplace & Discovery

**FR-MKT-001 — Coach Search & Filtering**
The marketplace must allow any visitor (guest or authenticated) to search and filter coaches by: city, district/quarter, language(s) spoken, price range, age groups coached, skill specialization, and star rating. Default sort order is distance from the searcher's location. Users may re-sort by rating or price.

**FR-MKT-002 — Coach Profile**
Each coach profile must display: full name, bio, languages spoken, specialties, pricing structure, location, availability status, aggregate star rating and review count, verification badge, capability badges, and a media gallery.

**FR-MKT-003 — Coach Verification Badges**
The system must display the coach's verification tier on both profile and marketplace card. Tier is granted by admin action only — never self-assigned.

**FR-MKT-004 — Capability Badges**
The system must display capability badges indicating which premium tools the coach actively uses: Video Feedback, Performance Reports, Homework Assignments, Skills Radar, Verified Identity.

**FR-MKT-005 — Guest Restrictions**
Guests may browse and view coach profiles. Guests may not contact a coach, initiate a booking, or submit a review.

**FR-MKT-006 — Free-Text Contact Detail Sanitization**
The system must automatically detect and replace email addresses and phone numbers in all coach-provided free-text fields (bio, session descriptions, notes, messages) with a redaction notice (e.g., `[contact detail removed]`). The input form must display a visible warning to coaches that contact details are not permitted in free-text areas. This rule applies to all free-text inputs across the platform.

**FR-MKT-007 — Coach Self-Registration & Onboarding**
Coaches self-register without requiring admin approval. Onboarding flow:
1. Account creation: name, email, password, phone number.
2. Basic verification: email confirmation + phone OTP. Profile is not publicly listed until Basic verification is complete.
3. Profile builder: bio, specialties, languages, location, age groups coached, pricing, and media gallery.
4. Availability setup: define initial availability windows before the profile goes live.
5. Subscription selection: Scout (free) is the default; upgrade to Instructor or Academy is optional at onboarding or at any time after.

When a paid subscription lapses, the coach profile remains publicly listed on the Scout tier with Scout-level capabilities.

---

### 5.2 Booking & Scheduling

**FR-BKG-001 — Booking Credit Enforcement**
The system must perform a hard block on booking requests if the parent/player's session credits are fully exhausted. The session pack status must be visible before booking (e.g., "7 of 10 sessions used"). A booking cannot be submitted with zero remaining credits.

**FR-BKG-002 — Request/Approval Workflow**
1. Parent/player selects an available slot. System validates credit availability.
2. Request submitted → state: `REQUESTED`.
3. Coach receives notification; accepts or declines.
4. On acceptance: payment processed or credit locked → state: `CONFIRMED`.
5. On decline: state → `DECLINED`; parent notified.

**FR-BKG-003 — Booking State Machine**
The system must manage the full booking lifecycle through the following states:

| State | Description |
|---|---|
| `REQUESTED` | Parent/player submitted request; credit checked, not yet locked. |
| `ACCEPTED` | Coach accepted; session reserved. |
| `DECLINED` | Coach declined; no credit locked. |
| `PAYMENT_PENDING` | Awaiting payment processing. |
| `CONFIRMED` | Payment processed; session confirmed. |
| `UPCOMING` | Auto-transitioned 24h before session start; final reminders sent. |
| `IN_PROGRESS` | Coach tapped [Start Session]; timestamp recorded. |
| `COMPLETED_PENDING_CONFIRMATION` | Coach submitted session as complete (Quick Complete Mode); credit deduction pending parent/player confirmation; auto-confirms to `COMPLETED` after 24 hours of no response. |
| `COMPLETED` | Session concluded; session credit deducted. |
| `CANCELLED_PARENT` | Cancelled by parent; refund rules applied per cancellation window. |
| `CANCELLED_COACH` | Cancelled by coach; refund rules applied; reliability strike if <24h notice. |
| `NO_SHOW_PLAYER` | Player absent after 15-minute grace period; session marked completed, credit deducted. |
| `NO_SHOW_COACH` | Coach did not start session; automatic full refund issued; reliability strike recorded. |
| `DISPUTED` | Parent flagged session; credit deduction frozen; funds frozen pending admin review. |
| `REFUND_PENDING` | Refund initiated; awaiting processing. |
| `REFUNDED` | Refund completed. |

Credit deduction occurs **only** at `COMPLETED`. The `DISPUTED` state freezes deduction until admin resolution.

**FR-BKG-004 — Timezone Management (Pitch-First Protocol)**
- All timestamps stored in UTC using `TIMESTAMPTZ`.
- All session times displayed in the **Pitch Timezone** (geographic location of the training ground) by default.
- Every `Session` and `CoachProfile` entity must store a `canonical_timezone` field (e.g., `Europe/Berlin`).
- Parents and players may toggle to "My Current Time" view.
- On login, the system must notify users if their browser timezone differs from their profile or pitch timezone.
- Frontend timezone handling via Luxon or Day.js.

**FR-BKG-005 — Rescheduling (One-Tap RSVP)**
A parent may request a session time change via a "Request Change" button. The parent proposes a new time; the coach receives a notification and can accept (session time updates automatically) or decline (original time retained).

**FR-BKG-006 — Google/Apple Calendar Sync** *(Post-MVP)*
Two-way calendar sync with Google Calendar and Apple Calendar is deferred to post-MVP. MVP availability management is handled via an in-app calendar view.

**FR-BKG-007 — Coach Availability Management**
Coaches must be able to define recurring availability windows and manually block time for personal commitments or travel. Blocked time is unavailable for booking requests. Availability is managed through the in-app calendar view.

**FR-BKG-008 — Automated Reminders**
The system must send automated notifications to parent and player at configurable intervals before a session. Minimum: 24 hours before and 2 hours before.

**FR-BKG-009 — Session Duplication**
Coaches must be able to duplicate a booked session to the following week ("Repeat for next week") for recurring clients without re-entering session details.

**FR-BKG-010 — Session Completion Workflows**
Two completion paths are supported:

- **Live Session Mode:** Coach taps [Start Session] on arrival (creates audit timestamp, state → `IN_PROGRESS`). Drill-by-drill live timer with haptic transition alerts and outdoor-optimised display guides the session. Coach taps [End Session]; 30-second wrap-up triggered.
- **Quick Complete Mode:** For coaches who do not use live tracking. Coach taps [Session Completed] after the fact. The system sends a confirmation prompt to the parent/player. Session only transitions to `COMPLETED` and deducts the credit after the parent/player confirmation is received. This confirmation is a required gate, not an optional step.

**FR-BKG-011 — Scheduling UX by Role**
- **Coach view ("Command Center"):** All active clients, schedule gaps, projected revenue for the current week.
- **Parent view:** Only their child's sessions and remaining session credits.
- **Pitch-side constraint:** The Start/End session and 30-second wrap-up workflow must be completable in under 30 seconds, one-handed, on a mobile device outdoors.

**FR-BKG-012 — Quick Complete Parent Confirmation Gate**
In Quick Complete Mode, session credit deduction is gated behind explicit parent/player confirmation. Session transitions to `COMPLETED` only after confirmation is received. If no confirmation or dispute is received within 24 hours, the session auto-confirms to `COMPLETED` and the credit is deducted.

**FR-BKG-013 — Coach Command Center: Projected Revenue**
The coach scheduling view must display total projected revenue for the current week based on confirmed upcoming sessions, alongside schedule gaps.

**FR-BKG-014 — Bulk Session Request**
Parents must be able to select multiple available time slots from a coach's calendar and submit them as a single grouped request (maximum batch size configurable via platform config; default 5). The coach receives one grouped notification and may accept all slots at once or respond to each individually. A single payment transaction covers all accepted bookings in a batch.

---

### 5.3 Session Builder & Drill Library

**FR-SES-001 — Session Block Structure**
Sessions use four default blocks. Blocks are customizable in duration and content:

| Block | Default Duration |
|---|---|
| Warm-Up | 10 min |
| Technical Foundation | 15 min |
| Game Intensity | 25 min |
| Cool-Down & Review | 10 min |

**FR-SES-002 — Development Focus Selection**
Each session begins with a Development Focus selection. The coach selects one or more focus areas (e.g., Weak Foot, Finishing Under Pressure, First Touch, 1v1 Confidence). Drill recommendations and Session DNA weighting adapt to the selected focus.

**FR-SES-003 — Intelligent Drill Suggestions**
The system must recommend drills based on: selected development focus, player's neglected skills (from SLU history), recent training load, player age group, difficulty tier, and available equipment. Coaches may accept, substitute, or ignore suggestions.

**FR-SES-004 — Session DNA Analysis**
As drills are added, the system generates a live "Session DNA" summary showing balance across five dimensions: Technical, Physical, Cognitive, Match Realism, and Weak Foot Focus. Each dimension scored 0–100. This is a distinct construct from the Player Skills Radar.

**FR-SES-005 — Drag-and-Drop Session Management**
Coaches must be able to reorder drills within and across session blocks via drag-and-drop.

**FR-SES-006 — Equipment Packing List**
The system must automatically aggregate equipment requirements across all drills in a session into a single packing list. Generated on session save; viewable before the session starts.

**FR-SES-007 — Session Templates**
Coaches must be able to save any session as a reusable template stored in their private vault. Templates may be deployed to individual players with per-deployment customisation (duration, notes, focus areas, homework) without altering the original template.

**FR-SES-008 — 30-Second Post-Session Wrap-Up**
Triggered immediately after session completion in both modes. Required steps:
- Attendance confirmation (checkbox, mandatory).
- Quick ratings: Effort, Focus, Technique — 1–5 stars each.
- Optional voice-to-text session notes.
- Optional homework assignment (1–2 drills from the library).

These ratings are qualitative feedback only. They are **not** used to calculate Skills Radar (1–100) scores.

**FR-SES-009 — Homework Assignment**
Coaches may assign 1–2 drills as homework to a specific player. Players see only drills explicitly assigned to them — they cannot browse the full library. Homework access terminates when the player's paid session pack is exhausted.

**FR-SES-010 — Tier Gating**
The Session Builder (including Intelligent Drill Suggestions, Session DNA, Equipment Packing Lists, and Session Templates) is available to coaches on the **Instructor (Pro)** tier and above. Scout (Free) coaches have access to basic scheduling and booking only.

---

**FR-DRL-001 — Drill Library Architecture**
Two library types:
- **Platform Library:** Centrally managed; available to all coaches; read-only (coaches may clone).
- **Coach Private Library:** Coach-owned drills and clones; counts against the coach's storage quota.

**FR-DRL-002 — Drill Metadata Schema**
Each drill must store the following metadata for SLU calculation and display:

| Field | Description |
|---|---|
| Primary Skills | Main developmental outcomes (from 15-skill taxonomy) |
| Secondary Skills | Supporting developmental outcomes |
| Skill Weighting | Per-skill contribution weight |
| Rep Density | Estimated reps per minute |
| Intensity | Physical load, 1–5 |
| Pressure Level | Opposition/time pressure, 1–5 |
| Cognitive Load | Decision complexity, 1–5 |
| Match Realism | Match transfer similarity, 1–5 |
| Weak Foot Bias | Left / Right / Both / None |
| Difficulty Tier | U8 → Pro |
| Equipment Required | List of required equipment |
| Recommended Group Size | Solo / Pair / Group |
| Video Demo | 15-second silent loop |
| Coaching Points | 3–4 concise bullet points |

**FR-DRL-003 — Drill Card UI**
Each drill card (mobile-first) displays: 15-second silent autoplay loop, coaching points, setup diagram, equipment list, development summary (skills, intensity, match realism, weak foot focus), and expected SLU exposure estimate (e.g., "Estimated 120 weak-foot contacts in 8 min").

**FR-DRL-004 — Drill Operations**
Coaches must be able to: search and filter drills by skill, difficulty, and equipment; tag and categorise drills by development intent (specific tags such as "Scanning", "First touch under pressure", "Weak foot finishing" — not generic labels alone); clone platform drills into their private library.

**FR-DRL-005 — Foundation 20 (Launch Content)**
The platform launches with 20 professionally curated drills in the platform library:

| Pack | Focus |
|---|---|
| The Master Touch | Ball mastery and close control |
| The Sniper | Ball striking and weak-foot development |
| The Escape Artist | 1v1 dominance and deceptive turns |
| The Wall | Receiving on the move and directional touches |

**FR-DRL-006 — Custom Drill Uploads**
Coaches on paid tiers (Instructor, Academy) may upload custom drill videos. Upload limits governed by the coach's storage quota. All uploaded videos transcoded to 720p maximum.

---

### 5.4 Player Development Intelligence

Two distinct systems underpin player development tracking. They must not be conflated:

| System | Measures | Generated By |
|---|---|---|
| **Skill Load Units (SLU)** | Training *volume and exposure* per skill | Automatically from session completion data |
| **Skills Radar ("Big Test")** | Player *ability* per skill (1–100) | Coach-entered via standardised assessment |

**FR-DEV-001 — Skill Taxonomy**
The platform tracks development across 15 skills. The taxonomy must be extensible for future additions without breaking existing data:

| Code | Skill | Code | Skill |
|---|---|---|---|
| PAC | Pace | FIN | Finishing |
| SHO | Shooting | 1V1 | One-on-Ones |
| PAS | Passing | HED | Heading |
| DRI | Dribbling | CRO | Crossing |
| PHY | Physicality | IBS | In-Box Shooting |
| DEF | Defending | OBS | Out-Box Shooting |
| WEF | Weak Foot | FKI | Free Kicks |
| F1T | First Touch | | |

**FR-DEV-002 — SLU Automatic Generation**
Upon session completion, the system automatically calculates SLUs for each skill trained. SLUs represent estimated developmental exposure — not ability. Calculation inputs: drill duration, skill weighting, rep density, intensity, pressure level, and match realism modifiers. Coaches are never required to manually count repetitions. The SLU formula is internal and not exposed to users.

**FR-DEV-003 — SLU Snapshot Integrity**
SLU values must be baked into summary tables (`player_skill_stats`) at the moment a session reaches `COMPLETED`. Historical SLU data is permanent — subsequent changes to drill metadata must not retroactively alter prior SLU records.

**FR-DEV-004 — Weekly Skill Exposure Dashboard**
Players, parents, and coaches must be able to view: cumulative SLU per skill, trends over time (line graphs, stacked weekly bars, rolling averages, radar charts for volume), weekly consistency, and progress toward coach-defined SLU targets. Data is aggregated across all coaches training the player — no per-coach silo.

**FR-DEV-005 — Neglected Skill Detection**
The system must proactively flag skills that have fallen below target exposure thresholds (e.g., "Defensive exposure decreased 38% this week", "Weak foot training has not reached target load in 10 days"). Alerts surface in the coach dashboard and player portal.

**FR-DEV-006 — Coach-Defined SLU Targets**
Coaches may define weekly SLU targets per skill for a player. Progress updates automatically as sessions complete.

**FR-DEV-007 — Skills Radar ("Big Test") — Assessment Entry**
Coaches periodically conduct standardised Big Test assessments and enter results. Each skill is scored on the 1–100 scale using a weighted composite:

| Component | Weight |
|---|---|
| Objective assessment results | 50% |
| Match observation | 30% |
| Coach technical evaluation | 20% |

Scores must be grounded in measurable evidence (coached drills, match observation, video evidence) — not subjective estimation. The platform must enforce use of standardised scoring rubrics, not allow arbitrary scoring.

**MVP constraint:** Automated benchmark drills (e.g., timed toe-tap tests) are not included in MVP. Assessment results are based on coach-led drills and observations recorded in the platform.

**FR-DEV-008 — Universal Scoring Scale**
All Skills Radar scores use the 1–100 scale:

| Range | Level |
|---|---|
| 90–100 | Elite |
| 80–89 | Excellent |
| 70–79 | Advanced |
| 60–69 | Intermediate |
| 50–59 | Developing |
| 40–49 | Beginner |
| Below 40 | Very Weak |

**FR-DEV-009 — Multi-Coach Cumulative Radar**
All assessment entries from all coaches contribute to the same unified Skills Radar. There is no concept of a primary coach for data ownership. The radar reflects cumulative ability across the ecosystem.

**FR-DEV-010 — Coach Score Normalisation** *(Post-MVP)*
Detection of scoring bias (lenient/strict coaches, score inflation/compression) and cross-coach normalisation are deferred to post-MVP. Raw scores are used at launch.

**FR-DEV-011 — Development Correlation Engine** *(Academy tier only)*
The system correlates accumulated SLU (volume) with Skills Radar improvements (ability) and surfaces insights (e.g., "Weak foot rating improved by 5 points after 210 WEF-SLU over 5 weeks"). Available to Academy (Elite) coaches only.

**FR-DEV-012 — Radar Chart Display Rules**
- Interactive radar supports all 15 skills.
- Coaches select which skills to test and display per player. The chart geometry adjusts to the selected skill set.
- Each skill node displays: a confidence indicator (based on assessment frequency and data volume) and a "last updated" timestamp.
- The radar shows current score vs. baseline (first-ever assessment) to visualise growth.
- The player's baseline assessment is recorded permanently and used as the comparison point in all subsequent reports.

**FR-DEV-013 — PDF Performance Report**
Coaches may generate a PDF Performance Index report per player. Report contents:
- Player name, date, coach name, report focus.
- Coach branding (logo, brand colours) — **Academy tier only**.
- Performance radar for coach-selected skills.
- Baseline vs. current scores with automated improvement percentage.
- Coach's qualitative "Next Steps" summary.

Reports are downloadable by the parent. Standard PDF reports available on Instructor tier; branded reports require Academy tier.

**FR-DEV-014 — Unified Player Timeline**
A chronological timeline per player recording all event types: sessions, homework assignments, video uploads, coach feedback, Big Test assessments, milestones, payments, and reviews. Coach access expires after a configurable inactivity period (no active paid session relationship — see FR-PLT-001). Parents have full access at all times.

---

### 5.5 Player Portal

**FR-POR-001 — Session Pack Dashboard**
The parent view must display a visual session pack tracker showing credits used vs. total (e.g., "7 of 10 sessions used"). Visible before initiating any booking request.

**FR-POR-002 — Upcoming Sessions View**
Parents and players see scheduled sessions in the Pitch Timezone by default. A toggle to "My Current Time" is available. System warns if browser timezone differs from pitch timezone.

**FR-POR-003 — One-Tap Rescheduling**
Parents may suggest a session time change from the portal via "Request Change". The coach receives the proposal and accepts or declines (see FR-BKG-005).

**FR-POR-004 — Locker Room (Homework)**
Players access a dedicated "Locker Room" screen showing only drills explicitly assigned to them by a coach. Each drill displays the 15-second pro-demo loop. Players cannot browse the full drill library. Access terminates when the player's paid session pack is exhausted.

**FR-POR-005 — Video Management**
Parents and players must be able to:
- View real-time storage and bandwidth quota usage (see FR-POR-012).
- Delete older videos to free up quota space (see FR-POR-013).
- View video status (pending parental approval, published, archived).

**FR-POR-006 — Parental Video Approval Workflow**
For minor players, any video uploaded or tagged by a coach must be approved by the parent before becoming visible to the player. The parent receives a notification and can approve or reject. Rejected videos are flagged for coach awareness but not auto-deleted.

**FR-POR-007 — Shadow Account Management**
A single parent login manages one or more player profiles. The parent dashboard must allow switching between player profiles without logging out. Each player profile is isolated from others in the same family account.

**FR-POR-008 — Multi-Coach Data Aggregation**
The player portal displays SLU data and Skills Radar scores cumulatively across all coaches. There is no per-coach filtered view — all data is unified on the player's profile.

**FR-POR-009 — Progress Report Access**
Parents can view and download PDF Performance Index reports generated by coaches. Parents cannot generate reports themselves — reports are coach-initiated.

**FR-POR-010 — Messaging Transparency**
All messages between a player and any coach are visible to the parent at all times. Applies to all minor players (under 18).

**FR-POR-011 — Minor Upload Prohibition**
Players under 18 are prohibited from uploading performance videos. The upload action is not displayed or accessible to minor player accounts. This is a hard platform restriction.

**FR-POR-012 — Real-Time Quota Display**
The player and coach portal must display real-time storage and bandwidth usage against the tier limit (e.g., "1.4 GB of 2 GB used"). Display updates after each upload or deletion.

**FR-POR-013 — Self-Service Video Deletion**
Players and coaches must be able to delete individual videos to free up quota. Deletion is permanent. Player-owned homework videos may only be deleted by the player or their parent. Coach-owned drill videos may only be deleted by the coach or an admin.

---

### 5.6 Video Module

**FR-VID-001 — Video Types & Limits**

| Type | Uploaded By | Max Duration | Max Size | Purpose |
|---|---|---|---|---|
| Homework | Player | 60s | 250MB | Player practice clip submission |
| Drill Demo | Coach | 120s | 500MB | Library drill demonstration |
| Coach Review | Coach | 300s | 1GB | Time-stamped player feedback |

**FR-VID-002 — Upload Architecture (Bunny.net)**
Asynchronous upload pipeline:
1. **Pre-flight:** Client calls `POST /video/upload/initiate { fileSize }`. Backend verifies remaining quota atomically and creates a quota reservation. Returns a signed upload URL.
2. **Upload:** Client uploads directly to Bunny.net.
3. **Transcoding:** Bunny.net transcodes to 720p maximum.
4. **Sync:** Bunny.net triggers a `video.transcoded` webhook. Backend converts the reservation to a permanent `VideoMetadata` record and updates storage usage.
5. **Reservation expiry:** If the webhook is not received within 60 minutes (configurable — see FR-VID-014), a background job releases the reserved quota automatically.

**FR-VID-003 — Video State Machine**
Every video asset must progress through a strict state machine:
`PENDING` → `UPLOADED` → `SCANNING` → `TRANSCODING` → `PUBLISHED`

Failure at any state locks the asset and notifies the uploader. A `REJECTED` state applies when a parent explicitly rejects a video submitted by a minor player (see FR-VID-012); a rejected video is invisible to the player and flagged for coach awareness and is not auto-deleted.

**FR-VID-004 — Storage Quotas by Tier**

| Tier | Storage |
|---|---|
| Scout Coach (Free) | 0 GB (platform drills only) |
| Instructor Coach (Pro) | 5 GB |
| Academy Coach (Elite) | 20 GB |
| Athlete Player | 2 GB |
| Semi-Pro Player | 4 GB |
| Pro Player | 7 GB |

**FR-VID-005 — Bandwidth Quotas by Tier**

| Tier | Monthly Bandwidth |
|---|---|
| Scout Coach | 5 GB/mo |
| Instructor Coach | 50 GB/mo |
| Academy Coach | 200 GB/mo |
| Athlete Player | 10 GB/mo |
| Semi-Pro Player | 25 GB/mo |
| Pro Player | 50 GB/mo |

Bandwidth is consumed when a player watches player-uploaded content and when any student watches a coach-uploaded drill.

**FR-VID-006 — Quota Enforcement (Concurrency-Safe)**
All storage and bandwidth updates must use atomic SQL increments to prevent race conditions in multi-node environments. The pre-flight check and quota reservation must occur in a single atomic transaction. Simultaneous uploads from multiple clients for the same user must not be able to exceed the quota.

**FR-VID-007 — Deduplication for Platform Drills**
Platform-provided drill videos use a global reference counter. A single physical file is stored; individual coach records point to it. The reference counter is incremented and decremented atomically. Physical deletion occurs only when the reference count reaches zero.

**FR-VID-008 — Streaming Security**
- All playback URLs are tokenised and signed by the application server.
- Signed tokens are optionally bound to the requester's IP address.
- Bunny.net Edge Rules restrict video chunk delivery to the platform's authorised domains only.
- Signed URLs expire **2 hours** after issuance (configurable — see FR-VID-014).

**FR-VID-009 — Video Privacy**
All videos are private by default. No public video feeds exist. Coach proprietary drill videos are not downloadable. Player-owned homework videos are downloadable by the player only.

**FR-VID-010 — Video Lifecycle States**

| State | Trigger | Effect |
|---|---|---|
| `ACTIVE` | Default post-publish | Playback permitted. |
| `LOCKED` | Subscription expires (day 0–30) | Playback URLs refused; video remains in storage. |
| `ARCHIVED` | 31–90 days after expiration | Moved to cold storage (Bunny.net Storage Zones). |
| `PURGED` | 90+ days non-payment, or within 30 days of account deletion | Permanent physical deletion from Bunny.net; metadata purged. |

**FR-VID-011 — Automated Content Moderation**
Every uploaded video must be asynchronously scanned for: nudity, violence, hate symbols, and explicit content. High-confidence flags result in: automatic asset lock, account suspension pending admin review, and admin notification. No video reaches `PUBLISHED` state without passing (or receiving an admin override of) the scan.

**FR-VID-012 — Minor Safety Gate**
Videos of minor players are set to `HIDDEN` status until the parent account explicitly approves them (see FR-POR-006). A hidden video cannot be played back regardless of signed URL validity.

**FR-VID-013 — Account Deletion Purge**
On account deletion request: access is revoked immediately. All videos owned by the user and all videos owned by player profiles managed under the user's parent account are physically purged from Bunny.net within 30 days. Metadata is purged within the same window.

**FR-VID-014 — Configurable Storage, Bandwidth & Lifecycle Parameters**
All quota values, size/duration limits, expiry windows, and retention periods must be stored in the configuration layer and modifiable by an admin without a code deployment. No quota or timeout value is hardcoded.

**FR-VID-015 — Video Deletion RBAC**
A video delete request is only authorised if: the requester is the video owner, OR the requester is a verified parent of the owner, OR the requester is a platform admin acting on moderation grounds.

**FR-VID-016 — Account Deletion: Shadow Account Cascade**
When a parent account is deleted, the purge cascades to all videos owned by every player profile managed under that parent account.

**FR-VID-017 — Upload Reservation Timeout**
If the Bunny.net `video.transcoded` webhook is not received within 60 minutes of a pre-flight reservation, a background job automatically releases the reserved quota. Timeout is configurable (see FR-VID-014).

**FR-VID-018 — Signed URL Expiry**
Playback signed URLs expire 2 hours after issuance. Configurable (see FR-VID-014).

**FR-VID-019 — HLS Segment Size**
HLS delivery must use 2-second segment sizes to support precise frame-seeking for slow-motion sports analysis scrubbing of coach review videos.

**FR-VID-020 — Reactive UI for Video Pipeline**
The frontend must use polling or WebSockets to update video status in real-time during asynchronous processing. A video card must reflect state transitions (`SCANNING` → `TRANSCODING` → `PUBLISHED`) without requiring a page reload.

---

### 5.7 Payments & Subscriptions

**FR-PAY-001 — Payment Provider (MVP)**
The platform uses **Stripe Connect** as the sole payment provider for MVP. PayPal support is deferred to post-MVP. Stripe Connect handles: coach payouts, subscription billing, and marketplace commission collection.

**FR-PAY-002 — Platform Commission**
An 8% platform commission (configurable — see FR-PLT-002) is automatically deducted from every session or session pack payment before funds are routed to the coach.

**FR-PAY-003 — Fee Transparency**
The coach revenue dashboard must display: gross revenue collected, platform commission deducted (8%), Stripe processing fees deducted (e.g., 2.9% + €0.30 per transaction), and net payout. All line items must be clearly labelled.

**FR-PAY-004 — Currency**
MVP supports EUR (€) only.

**FR-PAY-005 — Off-Platform Payment Prevention**
Soliciting or accepting payment outside the platform is a prohibited conduct violation. The platform displays a "Platform Payments Only" trust badge. Violations are subject to enforcement up to account termination.

**FR-PAY-006 — Coach Subscription Tiers**

| Tier | Billing Cycle |
|---|---|
| Scout (Free) | — |
| Instructor (Pro) | Monthly only |
| Academy (Elite) | Monthly only |

Coach subscriptions are monthly-only. No annual billing option for coaches at MVP. Tier prices are configurable via the admin config layer (see FR-PLT-001).

**FR-PAY-007 — Player Subscription Tiers**

| Tier | Billing Options |
|---|---|
| Athlete | Monthly, Quarterly, or Yearly |
| Semi-Pro | Yearly only |
| Pro | Yearly only |

Semi-Pro and Pro tiers require yearly commitment to justify long-term data storage and history portability costs. Prices are configurable.

**FR-PAY-008 — Long-Term Data Retention Incentive**
Non-yearly subscribers are subject to the 30-day video archive policy. Yearly subscribers receive 90-day video retention after subscription expiry — three times the grace window of non-yearly subscribers, incentivising annual commitment.

**FR-PAY-009 — Premium Feature Activation (Anti-Bypass)**
High-value coach features (Video Review Room, Advanced Analytics, Branded Reports) activate only for a specific player-coach pair where active, paid session credits exist. Features deactivate immediately when the paid session pack is exhausted or the subscription expires.

**FR-PAY-010 — Payment Lifecycle**
1. At `REQUESTED`: payment pre-authorised or session credit validated.
2. At `ACCEPTED`: payment captured or credit locked.
3. At `COMPLETED`: funds released to coach (minus commission); session credit deducted.
4. If `DISPUTED`: funds frozen pending admin resolution.

**FR-PAY-011 — Coach Pricing Autonomy**
Coaches set their own prices. The 8% commission is applied on top of whatever price the coach sets. Coaches may offer:
- **Per-session pricing:** Fixed price per individual session.
- **Session pack bundles:** Prepaid bundle of multiple sessions at a coach-defined price, which may include a discount vs. per-session rate.

Both models may coexist on the same coach profile.

**FR-PAY-012 — Cancellation & Refund Policy**

*Parent/Player cancellations:*

| Notice Given | Refund |
|---|---|
| > 24 hours | Full refund |
| 6–24 hours | 50% refund |
| < 6 hours | No refund |

*Coach cancellations:*

| Notice Given | Refund to Parent | Penalty |
|---|---|---|
| > 24 hours | Full refund | None |
| < 24 hours | Full refund | Reliability strike |

*No-shows:*

| Scenario | Outcome |
|---|---|
| Player no-show | Session marked `COMPLETED` after 15-minute grace; credit deducted. |
| Coach no-show | Automatic full refund; reliability strike issued. |

**FR-PAY-013 — Weather Cancellations**
The coach may proceed, reschedule, or cancel. If cancelled, standard refund rules apply.

**FR-PAY-014 — Financial Reporting**
- Parents: payment history with downloadable PDF receipt per session.
- Coaches: revenue dashboard (bookings, gross, commission, Stripe fees, net payout) with downloadable PDF receipts/invoices per payout.

**FR-PAY-015 — Dispute Management**
When a parent flags a session: state → `DISPUTED`; funds frozen; admin notification generated. Admin reviews the session audit trail (Start/End timestamps, attendance confirmation, coach and parent statements) and resolves in favour of coach or parent. Resolution triggers the appropriate financial action (see §5.10 FR-ADM-003).

**FR-PAY-016 — Reliability Strike System**
Reliability strikes are issued automatically on: coach no-show (`NO_SHOW_COACH`) and coach cancellation with less than 24 hours notice (`CANCELLED_COACH` within window).

Rules:
- Strikes expire after a configurable window (default: 90 days — see FR-PLT-001).
- **Coach visibility:** coaches see their own current strike count.
- **Parent visibility:** coach profile displays a summarised trust indicator (e.g., "2 reliability issues in the last 90 days").
- **Admin visibility:** full strike history with timestamps and triggering event.
- **Configurable thresholds:** default 3 strikes in the window → admin review alert; 5 strikes → automatic temporary suspension.
- On reinstatement after suspension: strike count resets to zero.

**FR-PAY-017 — Subscription Self-Service Management**
Coaches and players manage their own subscriptions from within the app:
- **Upgrades:** Take effect immediately.
- **Downgrades:** Scheduled to take effect at the end of the current paid period. Monthly subscribers cannot downgrade mid-month; quarterly mid-quarter; yearly mid-year.
- **Cancellations:** Follow the same rule as downgrades — access continues until end of current paid period.

---

### 5.8 Messaging

**FR-MSG-001 — Scope & Purpose**
In-platform messaging is scoped to: scheduling coordination, coaching feedback, and homework discussion. It is not a general-purpose chat replacement.

**FR-MSG-002 — Access Rules by Age Tier**
- Paid player tier required to message coaches.
- Under 10: no player messaging; communication through parent only.
- 10–12: parent-visible only; no direct player-to-coach messaging.
- 13–17: player may message coaches; all messages mandatory-visible to parent.
- 18+: unrestricted within platform scope.

**FR-MSG-003 — Abuse Detection & Reporting**
Users must be able to report a message for: abuse, harassment, or off-platform payment solicitation. Reported messages are queued for admin review. Automated detection must flag prohibited keywords (contact details, off-platform payment solicitation).

**FR-MSG-004 — Data Retention**
All messages are retained for 24 months from the date sent, then automatically purged.

---

### 5.9 Reviews & Ratings

**FR-REV-001 — Review Eligibility**
Only users with at least one completed, paid session and no active dispute may leave a review for a coach. Reviews cannot be submitted by users who have never transacted with the coach.

**FR-REV-002 — Review Structure**
Each review includes: star rating (1–5), text body, and optional coach reply. The coach reply is visible below the original review.

**FR-REV-003 — Review Moderation**
Admins may hide or permanently remove reviews that violate platform policy. Repeat abusive reviews flagged for admin review.

---

### 5.10 Admin

**FR-ADM-001 — Account Management**
Admins may: suspend or permanently ban accounts, re-activate suspended accounts, manage coach verification upgrades (Trusted, Featured).

**FR-ADM-002 — Content Moderation Queue**
Admins manage a moderation queue for: flagged videos, reported messages, reported reviews, and AI-flagged uploads awaiting human review. Admins may delete content, hide reviews, and apply tiered enforcement (Warning → Temporary Suspension → Permanent Ban).

**FR-ADM-003 — Dispute Resolution**
Admins receive notifications for all `DISPUTED` sessions. The admin view must display: session audit trail (Start/End timestamps, attendance confirmation), coach and parent statements, and session credit/payment status. Admin resolves in favour of coach or parent:
- Coach favour → session → `COMPLETED`; credit deducted.
- Parent favour → session → `REFUNDED`; full refund issued.

**FR-ADM-004 — Appeals**
Users subject to enforcement actions may submit one appeal within 14 days. The platform decision following appeal review is final. Appeals are visible in the admin moderation queue.

**FR-ADM-005 — Financial Oversight**
Admins may initiate manual refunds and view platform-wide financial reports: total GMV, commission collected, active subscriptions by tier, dispute rate.

**FR-ADM-006 — Reliability Strike Oversight**
Admins view full coach reliability strike history. Strike thresholds for triggering review or suspension are configurable (see FR-PLT-001).

---

### 5.11 User Access Control

**FR-USR-001 — One Parent Per Player (Enforced)**
The system must enforce a hard constraint of one parent account per player profile. Registration of a second parent account for an existing player must be rejected at the application layer.

**FR-USR-002 — Negative Permission Enforcement**
The system must enforce the following explicit access denials:
- Coaches cannot access player data without an active or previous paid session relationship (subject to timeline expiry).
- Coaches cannot export data for players outside their own client roster.
- Players cannot modify coach-owned session templates.
- Scout tier coaches cannot access Session Builder, Reports, Skills Radar, or Timeline features.

**FR-TSC-009 — Family-Level Data Isolation**
All database queries for player data must include a mandatory `parent_id` filter enforced at the application security layer. No coach or parent may retrieve player data outside their authorised scope, regardless of direct ID access attempts.

---

## 6. Trust, Safety & Compliance

**FR-TSC-001 — Prohibited Conduct**
The following conduct is prohibited and subject to enforcement:
- Harassment, hate speech, or threats.
- Sexual content or inappropriate content involving minors.
- Violence or threats of violence.
- Fraud or misrepresentation (identity, credentials, results).
- Soliciting or accepting off-platform payments.
- Sharing contact details in free-text areas to circumvent platform restrictions.

**FR-TSC-002 — Enforcement Tiers**

| Tier | Action | Trigger |
|---|---|---|
| 1 | Warning | Minor or first-time violation |
| 2 | Temporary suspension | Repeated or moderate violation |
| 3 | Permanent ban | Severe violation, minor safety breach, or repeated Tier 2 |

All enforcement actions logged with timestamp and reason.

**FR-TSC-003 — Appeals**
Users subject to any enforcement action may submit one appeal within 14 days. The platform's decision following appeal review is final.

**FR-TSC-004 — Automated Content Moderation**
All user-uploaded content must be asynchronously scanned for: nudity, violence, hate symbols, explicit content, and non-football inappropriate content. High-confidence flags → automatic asset lock + admin notification. No asset reaches published state without passing or receiving an admin override.

**FR-TSC-005 — User Reporting**
Any user must be able to report: abusive messages, inappropriate content, fraudulent coach behaviour, off-platform payment solicitation, and identity misrepresentation. Reports are queued in the admin moderation dashboard.

**FR-TSC-006 — Child Safeguarding**
- Age-based account and communication restrictions per §2.3 are mandatory and not user-configurable.
- Minor player videos are hidden until parent-approved.
- All coach-player messages involving minors are parent-visible in perpetuity.
- Admin may escalate safeguarding incidents to a permanent ban without a prior warning.
- No public-facing player profiles — player data is never visible to unauthenticated users.

**FR-TSC-007 — GDPR Compliance**
- **Data residency:** All user data and video assets stored in EU-region infrastructure only.
- **Legal documents:** Platform must publish and maintain: Terms of Service, Privacy Policy, Cookie Policy, Media Consent Policy, Data Processing Agreement, and Parent Consent Policy for minor accounts.
- **User consent:** All users must accept Terms of Service and Privacy Policy at registration. Parents must additionally accept the Parent Consent Policy before creating a minor player profile.
- **Data export:** Users may request a full export of their personal data, player history, and reports.
- **Right to erasure:** On deletion request, data becomes inaccessible immediately. Physical deletion completes within 30 days. Physical backups purged within 90 days. Scope: videos, reports, messages, timeline events, and backups.
- **Data retention schedule:**

| Data Type | Retention |
|---|---|
| Chat messages | 24 months |
| Performance / homework videos (non-yearly subscribers) | 30 days |
| Performance / homework videos (yearly subscribers) | 90 days after subscription expires |
| Development data (reports, radar, timeline) | Duration of active account |
| Physical backups post-deletion | 90 days |

**FR-TSC-008 — Terms & Usage Enforcement**
Terms of Service must be accepted at registration for all user types. No account becomes active without explicit acceptance. Full Terms & Usage document is maintained in `addendum.md`.

---

## 7. Non-Functional Requirements

**NFR-001 — Performance**
- Core pages (Marketplace, Profiles, Portals): load under 3 seconds on standard 4G/broadband.
- Video playback startup: under 2 seconds.
- SLU and development data queries: sub-second response times enforced via snapshot tables (no real-time historical joins).

**NFR-002 — Availability**
- Uptime target: 99.5% for core services.

**NFR-003 — Modular Monolith Architecture**
The backend is implemented as a modular monolith at MVP with clearly defined service boundaries (scheduling, payments, video, development intelligence). Boundaries must be designed to support future extraction into independent services without major refactoring.

**NFR-004 — Concurrency & Multi-Node Safety**
- All storage/bandwidth updates use atomic SQL increments (`UPDATE ... SET used = used + :amount WHERE (used + :amount) <= limit`).
- PostgreSQL `READ COMMITTED` or `REPEATABLE READ` isolation with explicit `SELECT ... FOR UPDATE` row locking where atomic increments are insufficient.
- Hibernate `@Version` mandatory on all metadata entities to prevent stale-data overwrites.

**NFR-005 — Database**
- PostgreSQL with `JSONB` for `drill_metadata`, `session_dna`, and `assessment_payloads`.
- JSONB fields mapped using `hypersistence-utils` for Java type-safety.
- All temporal fields use `TIMESTAMPTZ`.

**NFR-006 — Security**
- JWT-based RBAC with secure, encrypted token storage.
- All Hibernate queries for player data must include a mandatory `parent_id` filter (see FR-TSC-009).
- Playback URLs tokenised, signed, and IP-bound.
- Bunny.net Edge Rules enforce referrer whitelisting.

**NFR-007 — Observability**
- Centralised logging: Loki.
- Metrics: Prometheus / Grafana.
- Distributed tracing: Tempo.

**NFR-008 — Scalability**
- Media hosting and global edge delivery via Bunny.net.
- Architecture must support horizontal scaling of stateless application nodes.

---

## 8. Success Metrics

| Metric | Description | Counter-metric |
|---|---|---|
| Active coaches | Coaches with at least one completed session in the last 30 days | Coach churn rate |
| Booking completion rate | % of `CONFIRMED` sessions reaching `COMPLETED` | Dispute rate |
| Session pack exhaustion rate | % of purchased packs fully consumed | Refund rate |
| Player retention | % of players with activity in consecutive 30-day periods | Subscription cancellation rate |
| Reliability strike rate | Strikes issued per 100 sessions | Coach suspension rate |
| Video moderation accuracy | % of flagged content correctly identified | False positive rate |
| Onboarding completion | % of registered coaches who complete profile setup and go live | Drop-off rate per onboarding step |

---

## 9. Constraints & Assumptions

### 9.1 Technical Constraints

| Constraint | Detail |
|---|---|
| Frontend | Vue + Quasar; design system per `ui-design_v2.md` |
| Backend | Spring Boot (Java); modular monolith at MVP |
| Database | PostgreSQL; JSONB for drill metadata, session DNA, assessment payloads; `TIMESTAMPTZ` for all temporal fields; `hypersistence-utils` for JSONB mapping |
| Video hosting | Bunny.net (Stream API + Storage Zones); HLS delivery; H.264/AAC codec; 2-second HLS segments |
| Payments | Stripe Connect (MVP only) |
| Data residency | EU-region infrastructure only (GDPR) |
| Currency | EUR only at MVP |
| Video transcoding | 720p maximum output for all user-uploaded content |
| Concurrency safety | Atomic SQL increments; `SELECT FOR UPDATE`; Hibernate `@Version` on all metadata entities |
| Timezone storage | UTC everywhere; display in Pitch Timezone by default |
| Frontend timezone | Luxon or Day.js |
| Authentication | JWT-based RBAC with encrypted token storage |
| Observability | Loki, Prometheus/Grafana, Tempo |

### 9.2 Business Constraints

| Constraint | Detail |
|---|---|
| Geographic scope | Germany only at MVP |
| Coach model | Solopreneur independent coaches only; clubs and academies out of scope |
| Commission | 8% on all marketplace transactions (configurable) |
| Minor safeguarding | Full age-tier access control mandatory — not user-configurable |
| Platform payments | All transactions must flow through the platform |

### 9.3 Assumptions

| Assumption | Implication if wrong |
|---|---|
| Coaches are mobile-primary users on outdoor pitches | Pitch-side UX requirements become less critical |
| Parents have smartphones with reliable 4G | Video playback and confirmation flows may need offline fallback |
| German market has sufficient density of independent coaches in urban areas | Acquisition strategy requires revision; no architectural impact |
| One parent/guardian per player is acceptable at MVP | Requires future work if co-parenting requests surface post-launch |
| Bunny.net pricing and quota structures are stable at launch | Quotas are configurable to accommodate provider-side changes |

---

## 10. Out of Scope (MVP)

| Item | Reason |
|---|---|
| Team / club / academy management | Deliberate scope exclusion — solopreneur coaches only |
| Multi-country / multi-currency | Germany-first; EUR only |
| PayPal payment integration | Stripe Connect sufficient for MVP |
| Google / Apple calendar two-way sync | In-app calendar covers MVP; external sync post-MVP |
| Multi-guardian access per player | Complexity deferred; one parent per player at MVP |
| Coach score normalisation | Requires ecosystem-scale data; post-MVP |
| Automated benchmark drills | Skills Radar uses coach-led assessment at MVP |
| AI session recommendations | Architecture supports future extension; not built at MVP |
| Development forecasting / workload AI | Same as above |
| Sponsored coach listings | Future revenue stream |
| Social sharing of PDF reports | Nice-to-have; not a launch requirement |
| Native mobile app (iOS / Android) | Platform launches as a mobile-responsive web application |
