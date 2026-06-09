---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-06-09'
inputDocuments:
  - '_bmad-output/planning-artifacts/prds/prd-skillars-2026-06-08/prd.md'
  - '_bmad-output/planning-artifacts/prds/prd-skillars-2026-06-08/addendum.md'
  - '_bmad-output/planning-artifacts/ux-design-specification.md'
  - '_bmad-output/project-context.md'
workflowType: 'architecture'
project_name: 'Skillars'
user_name: 'Mbah'
date: '2026-06-09'
---

# Architecture Decision Document — Skillars

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- Platform module map and bounded context boundaries
- Feature configuration layer design (FR-PLT-001)
- JWT storage and family-level data isolation enforcement
- Booking state machine ownership and credit deduction sequence
- SLU snapshot integrity model
- Quota reservation atomicity (video pre-flight)
- Stripe Connect account type and payment flow
- Content moderation stack (CSAM + explicit video + chat)

**Important Decisions (Shape Architecture):**
- Cross-module event communication pattern
- Real-time UI update approach (SSE)
- Bunny.net upload protocol (TUS)
- Age-tier enforcement point (service layer via config)
- Async outbox pattern for scheduled jobs
- Email and push notification providers
- Contact detail sanitization placement

**Deferred Decisions (Post-MVP):**
- Automated grooming-risk scoring (requires production messaging data to calibrate; Phase 2)
- Coach/player call recording and transcription (new product capability not in MVP PRD scope; requires dedicated GDPR/consent design)
- Google/Apple calendar sync (FR-BKG-006 explicitly deferred)
- PayPal payment integration (FR-PAY-001 explicitly deferred)
- Stripe Custom connected accounts (Express chosen for MVP velocity)

---

### Data Architecture

**Decision: Platform Module Map**
Ten bounded contexts map directly to the eleven FR categories.
`platform.security` is the existing module; all others are new:

| Module | Package | Status | Bounded Context |
|---|---|---|---|
| `security` | `platform.security` | **Existing** | Auth, JWT, roles, user accounts, shadow accounts (parent→player linkage), age-tier enforcement |
| `marketplace` | `platform.marketplace` | New | Coach profiles, search, discovery, verification badges, capability badges, onboarding |
| `booking` | `platform.booking` | New | 14-state booking machine, scheduling, availability management, reliability strikes |
| `session` | `platform.session` | New | Session builder, drill library (platform + coach private), templates, 30-second wrap-up, SLU trigger |
| `development` | `platform.development` | New | SLU stats snapshots, Skills Radar assessments, Big Test, PDF reports, player timeline |
| `video` | `platform.video` | New | Video metadata, video state machine, quota management, Bunny.net webhook handler, minor safety gate |
| `payment` | `platform.payment` | New | Stripe Connect, subscriptions (coach + player tiers), session packs, refunds, revenue dashboard |
| `messaging` | `platform.messaging` | New | In-platform messaging, age-tier access control, Gemini moderation, abuse detection, 24-month retention |
| `admin` | `platform.admin` | New | Moderation queue, dispute resolution, enforcement actions, appeals, financial oversight |
| `config` | `platform.config` | New | Feature gate configuration layer — all DB-stored configurable values (FR-PLT-001) |

Infrastructure adapters (business-agnostic — zero platform imports, zero business rules):

| Adapter | Package | Provides |
|---|---|---|
| `blobstore` | `infrastructure.blobstore` | Existing S3/local file adapter (reference implementation) |
| `bunny` | `infrastructure.bunny` | Bunny.net: TUS upload URL generation, signed playback URL generation, Storage Zone lifecycle calls |
| `sanitizer` | `infrastructure.sanitizer` | Regex-based free-text contact detail scanner (email + phone pattern detection) |
| `arachnid` | `infrastructure.arachnid` | Project Arachnid Shield REST adapter — CSAM hash matching, returns match/no-match |
| `videointel` | `infrastructure.videointel` | Google Cloud Video Intelligence adapter — explicit content detection job submission and result polling |
| `ses` | `infrastructure.ses` | AWS SES v2 email dispatch adapter (`SesV2Client`); zero template logic |

**Decision: Feature Configuration Layer**
A `platform_config` table stores all configurable values as typed key-value pairs.
`platform.config.service.ConfigService` provides an in-memory cache with configurable TTL
(default 5 minutes), refreshed on demand via admin action or cache expiry.
All modules inject `ConfigService` — no module reads `application.yml` for business rules.
Admin-modifiable without code deployment or restart (FR-PLT-001).

Scope of config-layer values (non-exhaustive):
- Coach tier feature entitlements (Scout / Instructor / Academy)
- Player tier feature entitlements (Athlete / Semi-Pro / Pro)
- Quota values per tier (storage GB, bandwidth GB/month)
- Video limits (max size, max duration per type)
- Platform commission rate (default 8%)
- Reliability strike expiry window (default 90 days)
- Strike thresholds for admin review and auto-suspension
- Timeline access expiry after relationship ends
- Video signed URL TTL (default 2 hours)
- Video reservation timeout (default 60 minutes)
- Reminder intervals (default 24h + 2h before session)
- Message retention period (default 24 months)

**Decision: SLU Snapshot Integrity**
Upon a `Booking` entity transitioning to `COMPLETED`, a `@TransactionalEventListener`
in `platform.development` bakes SLU values into `player_skill_stats` rows — one row
per skill per session. These rows are immutable once written (no UPDATE permitted on
`player_skill_stats`). Subsequent changes to drill metadata do not retroactively
alter any existing SLU record (FR-DEV-003). Sub-second SLU queries are satisfied
by reading the snapshot table directly with no historical joins.

**Decision: Session Credit Tracking**
Session packs are a first-class entity: `SessionPack` (`coach_id`, `player_id`,
`total_credits`, `used_credits`, `pack_type` [BUNDLE | PER_SESSION], `purchased_at`,
`expires_at`). Credit deduction occurs **only** at `COMPLETED` state transition, using
an atomic SQL increment:
```sql
UPDATE session_packs
SET used_credits = used_credits + 1
WHERE id = :packId AND used_credits < total_credits
```
Both pack bundles and per-session pricing coexist — they differ only in
`total_credits` (1 for per-session, N for bundles).

**Decision: Video Quota Reservation**
`VideoQuotaReservation` entity stores in-flight upload reservations:
`user_id`, `reserved_bytes`, `reserved_at`, `expires_at`, `status` [ACTIVE | COMMITTED | EXPIRED].
Pre-flight check and reservation occur in a single `@Transactional` block using
`SELECT ... FOR UPDATE` on the user's quota row. Bunny.net `video.transcoded` webhook
transitions reservation to `COMMITTED` and updates `storage_used_bytes`. A
`@Scheduled` job releases `ACTIVE` reservations older than the configured timeout
(FR-VID-017, default 60 min) back to available quota.

---

### Authentication & Security

**Decision: JWT Storage**
Access tokens stored in HttpOnly + Secure + SameSite=Strict cookies (15-minute TTL).
Refresh tokens stored in `refresh_tokens` table with longer TTL; rotated on each use
(one-time use, invalidated on reuse detection). This satisfies OWASP standards for
web apps: XSS cannot read HttpOnly cookies; SameSite=Strict prevents CSRF.

**Decision: Family-Level Data Isolation**
Belt-and-suspenders enforcement (FR-TSC-009):
- **Layer 1 — Spring Security:** Custom `@PreAuthorize` SpEL expressions verify that
  the authenticated principal's `parent_id` scope covers the requested player resource.
- **Layer 2 — Service layer:** All repository calls for player-scoped data include an
  explicit `parent_id` parameter. No repository method for player data exists without
  a `parent_id` filter.
Neither layer alone is sufficient — both are required. A bypass of the security layer
would still be blocked by the repository contract.

**Decision: Age-Tier Access Control**
`AgePolicy` record in `platform.security.contract` (read from `platform.config.ConfigService`)
defines the four age brackets (U10, 10–12, 13–17, 18+) and their associated capability
sets. Enforcement occurs at **service layer only** — not at the REST layer — because
age-tier rules affect booking, messaging, and video modules simultaneously.
Age is set at registration by the parent account and cannot be modified by the player.

---

### API & Communication Patterns

**Decision: Cross-Module Event Communication**
Spring `ApplicationEvent` + `@TransactionalEventListener(phase = AFTER_COMMIT)` for
all cross-module domain events. Events fire only after the triggering transaction commits —
no phantom events on rollback. No external message broker at MVP.

Key domain events:
- `BookingCompletedEvent` (booking → development, video, payment)
- `SessionPackExhaustedEvent` (payment → video, messaging — access gates)
- `VideoPublishedEvent` (video → messaging — parent notification)
- `AccountDeletionRequestedEvent` (security → video, messaging, development — cascade purge)

**Decision: Real-Time UI Updates**
Server-Sent Events (SSE) via Spring's `SseEmitter` for:
- Video pipeline state transitions (`VideoStatusCard` reactive updates)
- Booking state transitions (`BookingStateChip` reactive updates)

SSE is simpler than WebSocket for unidirectional server-push; no additional
infrastructure; Vue handles `EventSource` natively. Client falls back to 2-second
polling if SSE connection drops (mobile network interruption on pitch).

**Decision: Webhook Authentication + Idempotency**
- Bunny.net: verify `X-BunnyNet-Signature` HMAC-SHA256 header before processing.
- Stripe: verify `Stripe-Signature` header via Stripe Java SDK `Webhook.constructEvent()`.
- Both: store processed webhook IDs in `processed_webhooks` table
  (`webhook_id`, `source`, `processed_at`) with a unique constraint.
  Duplicate delivery → idempotent no-op, 200 response.

**Decision: Contact Detail Sanitization**
`infrastructure.sanitizer.ContactDetailSanitizer` provides a stateless regex service
for email and phone pattern detection and redaction. Applied server-side at
service layer before persisting any free-text field across all modules.
Frontend calls `POST /api/util/sanitize-preview` for real-time feedback (UX only —
server-side is the security gate).

---

### Video & Content Moderation

**Decision: Bunny.net Upload Protocol**
TUS resumable upload protocol via Bunny.net's TUS endpoint.
`infrastructure.bunny.BunnyTusService` generates authenticated TUS upload URLs.
Client uses `tus-js-client` to upload directly to Bunny.net. Pre-flight quota
reservation occurs before the TUS URL is issued.

**Decision: Content Moderation Stack (Three Layers)**

```
Layer 1 — CSAM hash matching (infrastructure.arachnid)
  ├─ Triggered: immediately on UPLOADED state, before transcoding
  ├─ Provider: Project Arachnid Shield (free for qualifying platforms)
  ├─ Method: SHA-1 + MD5 hash of source file → REST API → MATCH / NO_MATCH
  └─ On MATCH: immediate LOCKED state + admin alert + legal notification protocol

Layer 2 — Explicit content detection (infrastructure.videointel)
  ├─ Triggered: after Arachnid clears (NO_MATCH), during SCANNING state
  ├─ Provider: Google Cloud Video Intelligence EXPLICIT_CONTENT_DETECTION (europe-west1)
  ├─ Method: async annotateVideo() job → poll via longrunning.Operations
  └─ On HIGH/VERY_LIKELY: LOCKED state + admin moderation queue

Layer 3 — Chat moderation (Gemini API, platform.messaging)
  ├─ Triggered: on every message sent involving a minor player (under 18)
  ├─ Provider: Gemini API (text classification)
  ├─ Method: classify for grooming patterns, off-platform solicitation, inappropriate content
  └─ On high-confidence flag: message quarantined + admin queue + parent alert
     Fail-closed: if Gemini unavailable, message is quarantined, not delivered
```

**Decision: Platform Drill Deduplication**
`ref_count` column on `PlatformDrillVideo` entity. Atomic increment on clone;
atomic decrement on deletion. Physical Bunny.net deletion scheduled only when
`ref_count = 0` (FR-VID-007).

**Decision: Video Lifecycle Scheduler**
`platform.video.service.VideoLifecycleScheduler` (`@Scheduled` + `SKIP LOCKED`):
ACTIVE→LOCKED (day 0), LOCKED→ARCHIVED (day 31), ARCHIVED→PURGED (day 91 or
account deletion cascade). All retention thresholds read from `platform.config`.

---

### Payments

**Decision: Stripe Connect Account Type**
Express connected accounts for coaches. Stripe handles KYC, identity verification,
payout UI, and compliance. Coaches onboard via Stripe's hosted OAuth flow.

**Decision: Payment Flow**
Stripe Connect Destination Charges. Platform retains commission (configurable,
default 8%) automatically. Remainder transferred to coach's Express account.
All amounts in EUR (FR-PAY-004).

---

### Infrastructure & Deployment

**Decision: Email Notifications**
AWS SES v2 (`eu-west-1`) via `infrastructure.ses.SesEmailService` using
`SesV2Client` (AWS SDK v2). Zero rendering logic in the adapter — template
content managed in SES. DKIM + SPF + DMARC configured on sending domain.

**Decision: Push Notifications**
Web Push API via Service Worker (PWA). No FCM/APNS — Skillars is a
mobile-responsive web app. Push subscription stored server-side per user device.

**Decision: Async Outbox Pattern**
`@Scheduled` + `SELECT ... FOR UPDATE SKIP LOCKED` for all durable async jobs.
Each platform module owns its own schedulers in `platform.{module}.service`.
Infrastructure adapters are invoked by schedulers; schedulers never live in infrastructure.

---

### Decision Impact Analysis

**Implementation Sequence:**
1. `platform.config` — feature gate table + ConfigService (all modules depend on this)
2. `platform.security` — extend: shadow accounts, age tiers, JWT cookie storage
3. `platform.marketplace` — coach profiles, search, verification badges
4. `platform.booking` — booking state machine, availability, session packs
5. `platform.session` — drill library, session builder, wrap-up, SLU trigger
6. `platform.development` — SLU snapshots, Skills Radar, PDF reports
7. `platform.video` — video pipeline, quota management, moderation chain
8. `platform.payment` — Stripe Connect, subscriptions, revenue dashboard
9. `platform.messaging` — messaging, Gemini moderation, retention scheduler
10. `platform.admin` — moderation queue, dispute resolution, financial oversight

Infrastructure adapters initialized alongside the first module that requires them.

**Cross-Component Dependencies:**
- All modules depend on `platform.config` before any tier gating works
- `platform.development` SLU baking depends on `BookingCompletedEvent` from `platform.booking`
- `platform.video` quota gating depends on `SessionPackExhaustedEvent` from `platform.payment`
- `platform.messaging` age-tier check depends on `AgePolicy` from `platform.security`
- Gemini moderation in `platform.messaging` must complete before message is persisted
- Video pipeline Layers 1 + 2 must complete before `platform.video` sets state to PUBLISHED
- All `@Scheduled` jobs depend on `platform.config` for retention/timeout values
- `platform.admin` aggregates flags from `platform.video`, `platform.messaging`, and `platform.booking`

## Implementation Patterns & Consistency Rules

### Critical Conflict Points Identified

14 areas where AI agents could diverge without explicit rules: REST path prefix,
YAML config namespace per module, booking state machine ownership, SLU calculation
module boundary, @Transactional scope for state transitions, age-tier enforcement
point, ConfigService usage pattern, SSE emitter lifecycle, ApplicationEvent payload
structure, error envelope format, API response shapes for key flows, external API
retry/fail strategy, DB table naming for new modules, and frontend API file and
store organisation.

---

### Naming Patterns

**REST Endpoint Naming:**
All Skillars API endpoints use `/api/{module}/{resource}` with lowercase kebab-case:
```
POST   /api/bookings                              ← state transitions via PATCH /api/bookings/{id}/state
GET    /api/bookings/{bookingId}
GET    /api/players/{playerId}/skill-stats
POST   /api/videos/upload/initiate
GET    /api/coaches/search
POST   /api/messages
POST   /api/util/sanitize-preview
```
Path parameters: `{camelCase}`. Query parameters: `camelCase`.
Never: `/api/booking/` (singular), `/api/Booking/`, `/api/booking_sessions/`.

**YAML Configuration Namespace:**
Every module uses `app.{module}.*`:
```yaml
app:
  booking:
    reminder-intervals-hours: [24, 2]
    no-show-grace-minutes: 15
  video:
    reservation-timeout-minutes: 60
    signed-url-ttl-seconds: 7200
    moderation:
      fail-closed: true
  payment:
    stripe:
      webhook-secret: ${STRIPE_WEBHOOK_SECRET}
  config:
    cache-ttl-seconds: 300
```
Never: `skillars.*`, `platform.*`, or flat keys without module prefix.

**Database Table Names (snake_case, plural):**

| Module | Tables |
|---|---|
| `security` | `users`, `roles`, `user_roles`, `player_profiles`, `parent_player_links`, `refresh_tokens` |
| `marketplace` | `coach_profiles`, `coach_specialties`, `coach_languages`, `verification_badges`, `coach_reviews` |
| `booking` | `bookings`, `session_availabilities`, `reliability_strikes` |
| `session` | `session_plans`, `session_blocks`, `drill_library`, `coach_drill_library`, `session_templates`, `homework_assignments` |
| `development` | `player_skill_stats`, `skills_radar_assessments`, `player_timelines` |
| `video` | `video_assets`, `platform_drill_videos`, `video_quota_reservations`, `processed_webhooks` |
| `payment` | `session_packs`, `stripe_subscriptions`, `payment_transactions` |
| `messaging` | `messages`, `message_flags` |
| `admin` | `moderation_queue_items`, `enforcement_actions`, `dispute_resolutions` |
| `config` | `platform_config` |

Constraint naming: `fk_{table}_{referenced_table}`, `idx_{table}_{column(s)}`, `uq_{table}_{column(s)}`.

**Java Class Names (suffix conventions):**

| Layer | Suffix | Example |
|---|---|---|
| REST Controller | `Resource` | `BookingResource`, `VideoResource` |
| Domain Service | `Service` | `BookingService`, `VideoQuotaService` |
| Scheduler | `Scheduler` | `VideoLifecycleScheduler`, `MessageRetentionScheduler` |
| JPA Entity | (none) | `Booking`, `VideoAsset`, `SessionPack` |
| Repository | `Repository` | `BookingRepository`, `VideoAssetRepository` |
| DTO (request) | `Request` | `InitiateUploadRequest`, `CreateBookingRequest` |
| DTO (response) | `Response` | `InitiateUploadResponse`, `BookingResponse` |
| Error Code enum | `ErrorCode` | `BookingErrorCode`, `VideoErrorCode` |
| Spring Event | `Event` | `BookingCompletedEvent`, `VideoPublishedEvent` |
| Config Properties | `Properties` | `VideoProperties`, `BookingProperties` |
| Spring Config | `Config` | `VideoConfig`, `BookingConfig` |

**ApplicationEvent Naming:** `{Subject}{PastTense}Event` — always past tense, always
in `platform.{module}.contract.event`:
```java
BookingCompletedEvent, SessionPackExhaustedEvent, VideoPublishedEvent, AccountDeletionRequestedEvent
// Not: BookingComplete, OnBookingCompleted, BookingCompletionEvent
```

**Error Code Enum Pattern:**
```java
public enum BookingErrorCode implements ErrorCode {
    BOOKING_NOT_FOUND, INSUFFICIENT_CREDITS, SLOT_UNAVAILABLE,
    INVALID_STATE_TRANSITION, DISPUTE_WINDOW_EXPIRED;
    @Override public String getErrorCode() { return this.name(); }
}
```

**SSE Event Type Naming:** `{module}.{subject}.{past-tense}` kebab-case:
`video.asset.state-changed`, `booking.state-changed`, `notification.push`

---

### Structure Patterns

**Booking State Machine Ownership:**
State transition logic lives **exclusively** in `BookingService`. No other class may
call `booking.setStatus()` directly. All state changes go through:
```java
bookingService.transition(bookingId, BookingEvent.COMPLETE, context);
```
Agents that bypass this method create silent state corruption.

**SLU Calculation Module Boundary:**
`platform.session` fires `SessionCompletedEvent` — it does NOT calculate SLUs.
`platform.development` listens via `@TransactionalEventListener(AFTER_COMMIT)` and
bakes SLUs into `player_skill_stats`. No SLU logic in `platform.session`.

**ConfigService Usage Pattern:**
Always inject and call per use — never cache in a field:
```java
// Correct
public long getStorageQuotaBytes(CoachTier tier) {
    return config.getLong("video.quota.coach." + tier.name().toLowerCase());
}
// Wrong — stale if admin changes config mid-run
private final long storageQuota; // populated in constructor
```

**Age-Tier Enforcement Point:** Service layer `@Service` only. Never in
`@RestController`, `@Entity`, or `@Repository`.

**Parent ID Filter — Repository Contract:**
```java
// Correct
Page<Booking> findByPlayerIdAndParentId(UUID playerId, UUID parentId, Pageable p);
// Wrong — bypasses family isolation
Page<Booking> findByPlayerId(UUID playerId, Pageable p);
```

**SSE Emitter Registry:** `SseEmitterRegistry` maintained per module, keyed by
`userId`. All state-change events route through the registry. Expired emitters
removed on `onCompletion()` and `onTimeout()` — never leaked.

**Test Naming:**
- Testcontainers + Spring context: `{ClassName}IT`
- Pure unit (no Spring): `{ClassName}Test`
- Base classes: `Base{Module}IT` (e.g., `BaseBookingIT`, `BaseVideoIT`)

---

### Format Patterns

**Error Response Envelope:** Always `ErrorDto` — never `ProblemDetail`:
```json
{ "helpCode": "Xk9mP",
  "errorMsg": { "key": "booking.insufficientCredits",
                "message": "You have no remaining session credits for this coach." },
  "fieldErrors": [] }
```
Every new exception type needs a registered `@ExceptionHandler` in `ApiAdvice`.
i18n keys: `{module}.*` namespace (`booking.*`, `video.*`, `payment.*`).

**Booking State — API Representation:**
Raw `BookingStatus` enum values are never exposed. Always map to `BookingStatusDto`:
```json
{ "status": { "value": "CONFIRMED", "displayLabel": "Confirmed",
              "displayColour": "accent-primary" } }
```

**Key API Response Shapes:**
```
POST /api/videos/upload/initiate → 200
{ "reservationId": "uuid", "tusUploadUrl": "https://...", "expiresAt": "ISO8601" }

GET /api/players/{id}/skill-stats → 200
{ "skills": [ { "code": "WEF", "currentScore": 64, "baselineScore": 41,
                "weeklySlus": 210, "lastAssessedAt": "ISO8601", "confidence": "HIGH" } ] }
```
All timestamps: ISO 8601 UTC strings. Never epoch milliseconds. Never `ZonedDateTime`
with offset in JSON.

**SSE Event Payload:**
```json
{ "type": "video.asset.state-changed",
  "payload": { "assetId": "uuid", "newState": "PUBLISHED", "updatedAt": "ISO8601" } }
```

---

### Process Patterns

**@Transactional Boundaries:**
External API calls (Stripe, Bunny.net, Gemini, Arachnid, Google Video Intel) are
**always outside** `@Transactional`. DB writes follow in a separate `@Transactional`:
```
// Booking ACCEPTED → payment captured:
1. stripe.capturePaymentIntent(intentId)   ← outside @Transactional
2. @Transactional: booking.status = CONFIRMED + sessionPack credit locked + event published
```

**Retry Pattern for External Adapters:**
All infrastructure adapter methods use `@Retryable` with YAML-configured backoff —
consistent across `infrastructure.bunny`, `infrastructure.arachnid`,
`infrastructure.videointel`, `infrastructure.ses`.

**Moderation Fail-Closed Rule:**
- Gemini unavailable → quarantine message; DO NOT deliver
- Arachnid unavailable → hold video in SCANNING; DO NOT advance to transcoding
- Google Video Intel fails → keep in SCANNING; alert admin; DO NOT auto-publish
Fail-open on a children's platform is a safeguarding violation.

**Streaming Anti-Pattern:**
Never `InputStream.readAllBytes()` on video or file content. All binary I/O uses
streaming or TUS chunked upload.

---

### Frontend Patterns

**API File Per Module:**
```
src/frontend/src/api/
  booking.api.js  video.api.js  marketplace.api.js  development.api.js
  payment.api.js  messaging.api.js  session.api.js  config.api.js  auth.api.js
```
No API call may exist outside these files.

**Pinia Store Per Domain:**
```
src/frontend/src/stores/
  booking.store.js  video.store.js  marketplace.store.js  development.store.js
  payment.store.js  messaging.store.js  session.store.js  auth.store.js
```
Stores do not import other stores. Raw Axios calls are forbidden in stores.

**SSE Connection Management:** One `useSseConnection` composable per module.
Reconnection uses exponential backoff (1s→2s→4s→max 30s). Falls back to 2-second
polling after 3 failed reconnects.

---

### Enforcement Guidelines

**All AI Agents MUST:**
- Route all booking state changes through `BookingService.transition()`
- Keep SLU calculation in `platform.development`, never `platform.session`
- Call `ConfigService` per use — never cache in a field
- Include `parentId` parameter on every player-scoped repository method
- Place external API calls outside `@Transactional`
- Use `ErrorDto` + registered `@ExceptionHandler` — never `ProblemDetail`
- Map `BookingStatus` to `BookingStatusDto` before including in any response
- Fail-closed on all moderation calls for minor player content
- Read all quota/retention/tier values from `ConfigService`, never from `application.yml`
- Never call `InputStream.readAllBytes()` on binary content

**Anti-Patterns:**
- Checking age tier in `@RestController`
- Player data query without `parent_id` filter
- Business rules in `infrastructure.*` packages
- Config values hardcoded in `application.yml`
- Direct Stripe/Bunny.net calls from `platform.*` (must use infrastructure adapters)
- `@EventListener` instead of `@TransactionalEventListener(AFTER_COMMIT)`
- Delivering a message to a minor without awaiting Gemini moderation result

## Starter Template Evaluation

### Primary Technology Domain

Full-stack modular monolith added to an existing Spring Boot 3.5 / Java 17 codebase with a
Quasar 2 / Vue 3.5 frontend. No external starter template CLI is applicable — the project
already exists with a defined DDD module scaffolding convention.

### Starter Options Considered

Since the project already exists with a defined architecture and toolchain, no external starter
template is evaluated. The "starter" is the project's own module scaffolding convention as
defined in `project-context.md`.

### Selected Starter: Existing DDD Module Convention

**Rationale for Selection:**
The project uses a Modular Monolith + DDD structure. Every new bounded context is created as
a module under `com.softropic.skillars.platform.{module}` following the same package hierarchy
and layer conventions as existing modules. This ensures AI agents implement consistently
without ambiguity about where code belongs.

**Architectural Decisions Established by Convention:**

**Language & Runtime:** Java 17 with `record` types for all DTOs.

**Build Tooling:** Maven (existing project build system).

**Module Internal Structure:** Every new module under `com.softropic.skillars.platform.{module}`
must adhere to:

| Layer | Package | Responsibility |
|---|---|---|
| Web | `api` | REST Controllers (`@RestController`, `@Observed`, `@PreAuthorize`), DTO mapping |
| Domain | `service` | Business logic, domain services, orchestration, schedulers |
| Persistence | `repo` | JPA Entities and Spring Data Repositories |
| Contract | `contract` | DTO records, Events, Exceptions |
| Config | `config` | Spring `@Configuration` beans for the module |

**Infrastructure vs Platform boundary:**
- `com.softropic.skillars.infrastructure` — provider adapters only: port interface, provider
  implementations, transport-layer types, provider-level exceptions, provider config, wiring.
  Zero business rules. Zero platform imports. Zero JPA entities.
- `com.softropic.skillars.platform.{module}` — everything else: entities, repos, domain
  services, schedulers, validation, quota logic, decorators.

**Persistence:** Flyway for all schema changes; Hibernate 6 entities with Lombok; MapStruct
for all Entity↔DTO conversions; `hypersistence-utils` for JSONB fields.

**Testing:** `@SpringBootTest` + `@Testcontainers`; Instancio for test data; AssertJ
assertions; Awaitility for async verification; WireMock for external API stubs.

**Observability:** `@Observed(name = "...")` on all resource methods; `@Slf4j` structured
logging; Micrometer metrics at service layer.

**Security:** `@PreAuthorize` using `SecurityConstants` on every endpoint — no exceptions.
No JPA entity may be returned directly from a `@RestController`.

**Frontend:** Quasar 2.16.0 · Vue 3.5 `<script setup>` on all components · Pinia stores for
shared state · all API calls in `src/frontend/src/api/*.api.js` · Prettier mandatory on all
`.js`, `.vue`, `.scss`, `.json` · `async/await` for all async operations.

**Note:** Module initialization (package creation, base `@Configuration` class, first Flyway
migration, `SecurityConstants` entries) should be the first implementation story for each
new bounded context.

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
11 categories, ~90 individual FRs covering:
- **Marketplace & Discovery (FR-MKT-001–007):** Coach search and filtering, public profiles,
  verification badges (Basic/Trusted/Featured), capability badges, guest restrictions,
  free-text contact detail sanitization, coach self-registration and onboarding.
- **Booking & Scheduling (FR-BKG-001–013):** 14-state booking state machine, credit
  enforcement, timezone management (Pitch-First Protocol, UTC storage), live session mode,
  quick complete mode with parent confirmation gate, automated reminders, coach availability
  management.
- **Session Builder & Drill Library (FR-SES-001–010, FR-DRL-001–006):** Four-block session
  structure, development focus selection, intelligent drill suggestions, Session DNA
  5-dimension analysis, drag-and-drop management, equipment packing lists, session templates,
  30-second wrap-up, homework assignment. Platform library + coach private library. Tier-gated
  (Instructor+).
- **Player Development Intelligence (FR-DEV-001–014):** Two distinct systems — SLU
  (training volume, auto-generated from session completion) and Skills Radar (assessed ability
  1–100, coach-entered). 15-skill taxonomy. SLU snapshot immutability. Weekly exposure
  dashboard, neglected skill detection, PDF Performance Reports. Multi-coach cumulative radar.
- **Player Portal (FR-POR-001–013):** Session pack tracker, Locker Room (homework), video
  management, parental approval workflow, shadow account management, real-time quota display.
- **Video Module (FR-VID-001–020):** Three video types (Homework 60s/250MB, Drill Demo
  120s/500MB, Coach Review 300s/1GB). Bunny.net async upload pipeline with webhook sync.
  Video state machine (PENDING→UPLOADED→SCANNING→TRANSCODING→PUBLISHED). Storage and
  bandwidth quotas by tier. Platform drill deduplication (reference counting). Signed playback
  URLs (2h TTL). HLS delivery with 2-second segments. Async content moderation scan. Minor
  safety gate (parent approval before player visibility). Account deletion cascade purge.
- **Payments & Subscriptions (FR-PAY-001–017):** Stripe Connect marketplace payments (EUR
  only). 8% configurable platform commission. Coach tiers (Scout/Instructor/Academy, monthly).
  Player tiers (Athlete/Semi-Pro/Pro, monthly or yearly). Session packs and per-session
  pricing coexisting. Refund policy matrix. Reliability strike system (configurable thresholds,
  90-day expiry). Subscription self-service (upgrades immediate; downgrades at period end).
- **Messaging (FR-MSG-001–004):** In-platform scoped messaging. Age-tiered access (U10:
  none, 10–12: parent-visible only, 13–17: player permitted + parent-visible, 18+: unrestricted).
  Abuse detection. 24-month retention.
- **Reviews & Ratings (FR-REV-001–003):** Eligibility gated by completed paid session.
  Star rating + text + coach reply. Admin moderation.
- **Admin (FR-ADM-001–006):** Account management, moderation queue, dispute resolution
  with audit trail, appeals (14-day window, final), financial oversight.
- **User Access Control (FR-USR-001–002, FR-TSC-009):** One parent per player (enforced).
  Negative permission enforcement (coach access gated by active/past paid relationship).
  Mandatory parent_id filter on ALL player data queries.
- **Platform Configuration (FR-PLT-001–002):** ALL tier entitlements, quota values, retention
  windows, commission rates, strike thresholds stored in DB config layer — modifiable by admin
  without code deployment or restart.

**Non-Functional Requirements:**
- **Performance:** Core pages < 3s on 4G/broadband; video playback startup < 2s; SLU
  and development data queries sub-second (enforced via snapshot tables — no real-time
  historical joins).
- **Availability:** 99.5% core services uptime.
- **Architecture:** Modular monolith at MVP; module boundaries designed for future extraction.
- **Concurrency & Multi-Node Safety:** Atomic SQL increments for all quota/bandwidth updates;
  `SELECT FOR UPDATE` with `SKIP LOCKED` for outbox patterns; Hibernate `@Version` mandatory
  on all metadata entities.
- **Database:** PostgreSQL; JSONB for `drill_metadata`, `session_dna`, `assessment_payloads`;
  `TIMESTAMPTZ` for all temporal fields; `hypersistence-utils` for JSONB type-safety.
- **Security:** JWT-based RBAC; encrypted token storage; Bunny.net signed URLs (IP-bound,
  2h TTL); Bunny.net Edge Rules domain whitelist.
- **GDPR:** EU-region infrastructure only; right to erasure (30-day physical deletion,
  90-day backup purge); data export on request; data retention schedules enforced.
- **Observability:** Loki (logging), Prometheus/Grafana (metrics), Tempo (tracing).
- **Scalability:** Stateless application nodes; Bunny.net for media edge delivery.

**Scale & Complexity:**
- Primary domain: Full-stack modular monolith (Java 17 / Spring Boot 3.5 backend;
  Quasar 2/Vue 3 frontend; PostgreSQL; Bunny.net; Stripe Connect)
- Complexity level: **Enterprise** — multi-role marketplace, async media pipeline,
  3-dimensional tier system (coach tier × player tier × age tier), child safeguarding,
  GDPR compliance, payment marketplace with split payouts
- Estimated platform modules: 10 bounded contexts
- Estimated infrastructure adapters: 3
- External integrations: Bunny.net (Stream API + Storage Zones), Stripe Connect

### Technical Constraints & Dependencies

- **Language & Runtime:** Java 17; Spring Boot 3.5.11; Spring Cloud 2025.0.1
- **Frontend:** Quasar 2.16.0 (Vue 3.5.22); Vite; Pinia 3; Vue Router 4; Axios 1.2.1;
  Luxon or Day.js for timezone handling
- **Persistence:** PostgreSQL (JSONB + TIMESTAMPTZ); Flyway for all DDL; Spring Data JPA;
  Hibernate 6; hypersistence-utils for JSONB; Hibernate Envers for auditing
- **Tools:** Lombok; MapStruct 1.6.3; Spring Security 6; JJWT 0.13.0
- **Video Hosting:** Bunny.net — Stream API (upload, transcoding, HLS delivery, signed URLs)
  + Storage Zones (cold storage/archival); 2-second HLS segments; H.264/AAC; 720p max
- **Content Moderation:** Async video scanning pipeline (provider TBD — must integrate
  with Bunny.net webhook chain)
- **Payments:** Stripe Connect (marketplace split payouts, subscription billing, EUR only)
- **Observability:** Micrometer Tracing (OTEL), OTLP exporter, Prometheus, Loki Logback, Tempo
- **Testing:** JUnit 5, Testcontainers, Instancio, AssertJ, Mockito, WireMock, Awaitility
- **DDD Module Structure:** `com.softropic.skillars.platform.{module}` (business domains);
  `com.softropic.skillars.infrastructure` (business-agnostic technical adapters only)
- **Feature Gates:** FR-PLT-001 mandates all tier entitlements and configuration values
  reside in a DB-persisted config layer, not application properties or hardcoded values
- **Data Residency:** EU-region infrastructure only (GDPR constraint, non-negotiable)
- **Concurrency:** Atomic SQL increments for quota/bandwidth; `@Version` on all metadata
  entities; `SKIP LOCKED` for any scheduled job polling

### Cross-Cutting Concerns Identified

1. **Security & RBAC:** `@PreAuthorize` on every endpoint using `SecurityConstants`; JWT
   token validation; role-based feature visibility; never return JPA entities directly from
   `@RestController`.
2. **Family-Level Data Isolation:** Every Hibernate query for player data MUST include a
   mandatory `parent_id` filter enforced at the application security layer — this is a
   platform-wide invariant, not a module-specific concern.
3. **Age-Tier Access Control:** Messaging access, video upload/visibility, booking flows, and
   parental approval gates all depend on the player's age tier. Age is set at registration by
   the parent account and is not self-configurable by the player.
4. **Feature Configuration Layer:** A dedicated `platform.config` module provides all tier
   entitlements, quota values, commission rates, strike thresholds, and retention windows from
   the database. All modules query this layer at runtime — no feature flag or entitlement is
   hardcoded.
5. **Observability:** `@Observed(name = "...")` on all `@RestController` resource methods;
   structured logging via `@Slf4j`; Micrometer metrics instrumented at service layer for
   SLU calculation, video pipeline stages, and booking state transitions.
6. **Async Processing & Outbox:** SLU calculation on session completion, video pipeline
   webhook handling, notification dispatch, and GDPR deletion jobs all require async
   processing — outbox or event-driven patterns to ensure durability.
7. **Timezone Management:** All timestamps stored in UTC (`TIMESTAMPTZ`). Every `Session`
   and `CoachProfile` entity stores a `canonical_timezone`. Display layer converts to Pitch
   Timezone by default via frontend (Luxon/Day.js).
8. **Concurrency-Safe Quota Management:** Storage and bandwidth quota updates use atomic
   SQL increments. Pre-flight quota checks and reservations occur in a single atomic
   transaction using `SELECT FOR UPDATE`. Bunny.net webhook confirms and commits
   quota reservations.
9. **GDPR Right to Erasure:** Account deletion triggers cascading purge: immediate access
   revocation → video purge from Bunny.net (30 days) → message/timeline/metadata purge
   (30 days) → physical backup purge (90 days). Shadow account deletion cascades to all
   managed player profiles and their videos.
10. **Contact Detail Sanitization:** Free-text scanning for email/phone patterns in all
    free-text input fields (bio, session descriptions, messages) — must be enforced
    server-side, with frontend real-time feedback.

---

## Project Structure & Boundaries

### Requirements to Structure Mapping

| FR Category | Implementation Location |
|---|---|
| FR-PLT-001 Config Layer | `platform.config` + `platform_config` table |
| FR-MKT-001–007 Marketplace | `platform.marketplace` api/service/repo |
| FR-BKG-001–013 Booking | `platform.booking` api/service/repo + `BookingService` state machine |
| FR-SES-001–010 Session Builder | `platform.session` api/service/repo |
| FR-DRL-001–006 Drill Library | `platform.session.service.DrillLibraryService` + repo |
| FR-DEV-001–014 Development Intelligence | `platform.development` api/service/repo |
| FR-POR-001–013 Player Portal | Frontend `pages/parent/` + `pages/player/` + APIs from `development`, `booking`, `video` |
| FR-VID-001–020 Video Module | `platform.video` + `infrastructure.bunny` + `infrastructure.arachnid` + `infrastructure.videointel` |
| FR-PAY-001–017 Payments | `platform.payment` + Stripe Connect |
| FR-MSG-001–004 Messaging | `platform.messaging` + Gemini API |
| FR-REV-001–003 Reviews | `platform.marketplace.service.ReviewService` + repo |
| FR-ADM-001–006 Admin | `platform.admin` api/service/repo |
| FR-USR-001–002, FR-TSC-009 Access Control | `platform.security` (AgePolicy + parent_id enforcement) |
| FR-TSC-001–008 Trust & Safety | Cross-cutting: `platform.messaging` (Gemini), `platform.video` (moderation chain), `platform.admin` (enforcement) |

---

### Complete Project Directory Structure

```
src/
├── main/
│   ├── java/com/softropic/skillars/
│   │   │
│   │   ├── platform/
│   │   │   │
│   │   │   ├── config/                          ← Module 1 — Feature Gate Config Layer
│   │   │   │   ├── api/
│   │   │   │   │   └── ConfigResource.java      ← GET/PUT /api/config/values (admin only)
│   │   │   │   ├── service/
│   │   │   │   │   └── ConfigService.java       ← in-memory cache + DB; injected by all modules
│   │   │   │   ├── repo/
│   │   │   │   │   ├── PlatformConfig.java      ← @Entity (key, value, type, updatedAt)
│   │   │   │   │   └── PlatformConfigRepository.java
│   │   │   │   ├── contract/
│   │   │   │   │   └── ConfigErrorCode.java
│   │   │   │   └── config/
│   │   │   │       └── ConfigModuleConfig.java
│   │   │   │
│   │   │   ├── security/                        ← Module 2 — EXISTING (extend)
│   │   │   │   ├── api/                         ← (existing + new endpoints)
│   │   │   │   ├── service/
│   │   │   │   │   ├── (existing services)
│   │   │   │   │   ├── ShadowAccountService.java ← parent→player link management
│   │   │   │   │   └── AgePolicyService.java    ← reads AgePolicy from ConfigService
│   │   │   │   ├── repo/
│   │   │   │   │   ├── (existing entities)
│   │   │   │   │   ├── PlayerProfile.java       ← @Entity (age, position, parentId)
│   │   │   │   │   ├── ParentPlayerLink.java    ← @Entity (parentId, playerId)
│   │   │   │   │   └── RefreshToken.java        ← @Entity (token, userId, expiresAt, used)
│   │   │   │   └── contract/
│   │   │   │       └── AgePolicy.java           ← record; loaded from ConfigService
│   │   │   │
│   │   │   ├── marketplace/                     ← Module 3 — Coach Discovery
│   │   │   │   ├── api/
│   │   │   │   │   ├── CoachProfileResource.java
│   │   │   │   │   ├── MarketplaceSearchResource.java
│   │   │   │   │   └── ReviewResource.java
│   │   │   │   ├── service/
│   │   │   │   │   ├── CoachProfileService.java
│   │   │   │   │   ├── MarketplaceSearchService.java
│   │   │   │   │   ├── VerificationService.java ← badge grant/revoke (admin action)
│   │   │   │   │   └── ReviewService.java
│   │   │   │   ├── repo/
│   │   │   │   │   ├── CoachProfile.java
│   │   │   │   │   ├── CoachSpecialty.java
│   │   │   │   │   ├── VerificationBadge.java
│   │   │   │   │   ├── CoachReview.java
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── CoachProfileResponse.java
│   │   │   │   │   ├── CoachSearchRequest.java
│   │   │   │   │   ├── CoachSearchResponse.java
│   │   │   │   │   └── MarketplaceErrorCode.java
│   │   │   │   └── config/
│   │   │   │       └── MarketplaceConfig.java
│   │   │   │
│   │   │   ├── booking/                         ← Module 4 — 14-State Booking Machine
│   │   │   │   ├── api/
│   │   │   │   │   ├── BookingResource.java     ← PATCH /{id}/state for transitions
│   │   │   │   │   ├── AvailabilityResource.java
│   │   │   │   │   └── BookingSseResource.java  ← SSE stream per user
│   │   │   │   ├── service/
│   │   │   │   │   ├── BookingService.java      ← SOLE owner of state transitions
│   │   │   │   │   ├── AvailabilityService.java
│   │   │   │   │   ├── ReliabilityStrikeService.java
│   │   │   │   │   ├── BookingReminderScheduler.java
│   │   │   │   │   └── BookingSseEmitterRegistry.java
│   │   │   │   ├── repo/
│   │   │   │   │   ├── Booking.java             ← @Entity; @Version mandatory
│   │   │   │   │   ├── SessionAvailability.java
│   │   │   │   │   ├── ReliabilityStrike.java
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── BookingResponse.java
│   │   │   │   │   ├── BookingStatusDto.java    ← value + displayLabel + displayColour
│   │   │   │   │   ├── CreateBookingRequest.java
│   │   │   │   │   ├── BookingErrorCode.java
│   │   │   │   │   └── event/
│   │   │   │   │       ├── BookingCompletedEvent.java
│   │   │   │   │       └── BookingDisputedEvent.java
│   │   │   │   └── config/
│   │   │   │       ├── BookingConfig.java
│   │   │   │       └── BookingProperties.java
│   │   │   │
│   │   │   ├── session/                         ← Module 5 — Session Builder & Drills
│   │   │   │   ├── api/
│   │   │   │   │   ├── SessionPlanResource.java
│   │   │   │   │   ├── DrillLibraryResource.java
│   │   │   │   │   └── HomeworkResource.java
│   │   │   │   ├── service/
│   │   │   │   │   ├── SessionPlanService.java
│   │   │   │   │   ├── DrillLibraryService.java
│   │   │   │   │   ├── HomeworkService.java
│   │   │   │   │   └── WrapUpService.java       ← persists wrap-up; fires SessionCompletedEvent
│   │   │   │   ├── repo/
│   │   │   │   │   ├── SessionPlan.java         ← JSONB: session_dna
│   │   │   │   │   ├── SessionBlock.java
│   │   │   │   │   ├── DrillLibraryEntry.java   ← JSONB: drill_metadata
│   │   │   │   │   ├── CoachDrillLibraryEntry.java
│   │   │   │   │   ├── SessionTemplate.java
│   │   │   │   │   ├── HomeworkAssignment.java
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── SessionPlanResponse.java
│   │   │   │   │   ├── DrillResponse.java
│   │   │   │   │   ├── WrapUpRequest.java
│   │   │   │   │   ├── SessionErrorCode.java
│   │   │   │   │   └── event/
│   │   │   │   │       └── SessionCompletedEvent.java ← consumed by platform.development
│   │   │   │   └── config/
│   │   │   │       └── SessionConfig.java
│   │   │   │
│   │   │   ├── development/                     ← Module 6 — SLU + Skills Radar
│   │   │   │   ├── api/
│   │   │   │   │   ├── PlayerSkillStatsResource.java
│   │   │   │   │   ├── SkillsRadarResource.java
│   │   │   │   │   ├── PlayerTimelineResource.java
│   │   │   │   │   └── ReportResource.java      ← PDF generation
│   │   │   │   ├── service/
│   │   │   │   │   ├── SluCalculationService.java    ← @TransactionalEventListener on SessionCompletedEvent
│   │   │   │   │   ├── SkillsRadarService.java
│   │   │   │   │   ├── PlayerTimelineService.java
│   │   │   │   │   ├── PdfReportService.java
│   │   │   │   │   └── NeglectedSkillDetectionService.java
│   │   │   │   ├── repo/
│   │   │   │   │   ├── PlayerSkillStat.java     ← immutable once written; JSONB: assessment_payload
│   │   │   │   │   ├── SkillsRadarAssessment.java
│   │   │   │   │   ├── PlayerTimeline.java
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── PlayerSkillStatsResponse.java
│   │   │   │   │   ├── SkillsRadarResponse.java
│   │   │   │   │   ├── PlayerTimelineResponse.java
│   │   │   │   │   └── DevelopmentErrorCode.java
│   │   │   │   └── config/
│   │   │   │       └── DevelopmentConfig.java
│   │   │   │
│   │   │   ├── video/                           ← Module 7 — Video Pipeline & Quotas
│   │   │   │   ├── api/
│   │   │   │   │   ├── VideoResource.java       ← pre-flight, confirm, delete, signed URL
│   │   │   │   │   ├── VideoWebhookResource.java ← Bunny.net video.transcoded webhook
│   │   │   │   │   └── VideoSseResource.java    ← SSE stream for pipeline state
│   │   │   │   ├── service/
│   │   │   │   │   ├── VideoUploadService.java
│   │   │   │   │   ├── VideoQuotaService.java
│   │   │   │   │   ├── VideoModerationService.java  ← orchestrates Arachnid + VideoIntel
│   │   │   │   │   ├── VideoLifecycleScheduler.java ← ACTIVE→LOCKED→ARCHIVED→PURGED
│   │   │   │   │   └── VideoSseEmitterRegistry.java
│   │   │   │   ├── repo/
│   │   │   │   │   ├── VideoAsset.java          ← @Entity; @Version mandatory
│   │   │   │   │   ├── PlatformDrillVideo.java  ← ref_count column
│   │   │   │   │   ├── VideoQuotaReservation.java
│   │   │   │   │   ├── ProcessedWebhook.java    ← idempotency store
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── InitiateUploadRequest.java
│   │   │   │   │   ├── InitiateUploadResponse.java  ← reservationId + tusUploadUrl + expiresAt
│   │   │   │   │   ├── VideoAssetResponse.java
│   │   │   │   │   ├── VideoErrorCode.java
│   │   │   │   │   └── event/
│   │   │   │   │       └── VideoPublishedEvent.java
│   │   │   │   └── config/
│   │   │   │       ├── VideoConfig.java
│   │   │   │       └── VideoProperties.java
│   │   │   │
│   │   │   ├── payment/                         ← Module 8 — Stripe Connect & Subscriptions
│   │   │   │   ├── api/
│   │   │   │   │   ├── SubscriptionResource.java
│   │   │   │   │   ├── SessionPackResource.java
│   │   │   │   │   ├── RevenueResource.java
│   │   │   │   │   └── StripeWebhookResource.java
│   │   │   │   ├── service/
│   │   │   │   │   ├── SubscriptionService.java
│   │   │   │   │   ├── SessionPackService.java  ← atomic credit deduction
│   │   │   │   │   ├── RevenueService.java
│   │   │   │   │   └── StripeWebhookService.java
│   │   │   │   ├── repo/
│   │   │   │   │   ├── SessionPack.java         ← @Version mandatory
│   │   │   │   │   ├── StripeSubscription.java
│   │   │   │   │   ├── PaymentTransaction.java
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── SessionPackResponse.java
│   │   │   │   │   ├── SubscriptionResponse.java
│   │   │   │   │   ├── RevenueResponse.java
│   │   │   │   │   ├── PaymentErrorCode.java
│   │   │   │   │   └── event/
│   │   │   │   │       └── SessionPackExhaustedEvent.java
│   │   │   │   └── config/
│   │   │   │       ├── PaymentConfig.java
│   │   │   │       └── PaymentProperties.java   ← stripe keys, commission rate
│   │   │   │
│   │   │   ├── messaging/                       ← Module 9 — In-Platform Messaging
│   │   │   │   ├── api/
│   │   │   │   │   └── MessageResource.java
│   │   │   │   ├── service/
│   │   │   │   │   ├── MessageService.java      ← Gemini check before persist
│   │   │   │   │   ├── GeminiModerationService.java ← fail-closed on unavailability
│   │   │   │   │   └── MessageRetentionScheduler.java ← purge after 24 months
│   │   │   │   ├── repo/
│   │   │   │   │   ├── Message.java
│   │   │   │   │   ├── MessageFlag.java
│   │   │   │   │   └── (repositories)
│   │   │   │   ├── contract/
│   │   │   │   │   ├── MessageResponse.java
│   │   │   │   │   ├── SendMessageRequest.java
│   │   │   │   │   └── MessagingErrorCode.java
│   │   │   │   └── config/
│   │   │   │       ├── MessagingConfig.java
│   │   │   │       └── MessagingProperties.java ← Gemini model, fail-closed flag
│   │   │   │
│   │   │   └── admin/                           ← Module 10 — Platform Administration
│   │   │       ├── api/
│   │   │       │   ├── ModerationQueueResource.java
│   │   │       │   ├── DisputeResource.java
│   │   │       │   ├── EnforcementResource.java
│   │   │       │   └── FinancialOversightResource.java
│   │   │       ├── service/
│   │   │       │   ├── ModerationQueueService.java
│   │   │       │   ├── DisputeResolutionService.java
│   │   │       │   └── EnforcementService.java
│   │   │       ├── repo/
│   │   │       │   ├── ModerationQueueItem.java
│   │   │       │   ├── EnforcementAction.java
│   │   │       │   ├── DisputeResolution.java
│   │   │       │   └── (repositories)
│   │   │       ├── contract/
│   │   │       │   ├── ModerationItemResponse.java
│   │   │       │   ├── DisputeResponse.java
│   │   │       │   └── AdminErrorCode.java
│   │   │       └── config/
│   │   │           └── AdminConfig.java
│   │   │
│   │   └── infrastructure/
│   │       ├── blobstore/                       ← EXISTING reference implementation
│   │       ├── bunny/                           ← Bunny.net adapter
│   │       │   ├── BunnyTusService.java         ← TUS upload URL generation
│   │       │   ├── BunnySignedUrlService.java   ← signed playback URL generation
│   │       │   ├── BunnyStorageZoneService.java ← cold storage lifecycle calls
│   │       │   ├── BunnyProperties.java
│   │       │   ├── BunnyConfig.java
│   │       │   └── exception/BunnyProviderException.java
│   │       ├── sanitizer/                       ← Contact detail scanner
│   │       │   ├── ContactDetailSanitizer.java
│   │       │   └── SanitizerConfig.java
│   │       ├── arachnid/                        ← Project Arachnid Shield adapter
│   │       │   ├── ArachnidShieldService.java   ← hash submit → MATCH/NO_MATCH
│   │       │   ├── ArachnidProperties.java
│   │       │   ├── ArachnidConfig.java
│   │       │   └── exception/ArachnidException.java
│   │       ├── videointel/                      ← Google Cloud Video Intelligence adapter
│   │       │   ├── VideoIntelligenceService.java ← annotateVideo() + poll result
│   │       │   ├── VideoIntelligenceProperties.java ← project, region (europe-west1)
│   │       │   ├── VideoIntelligenceConfig.java
│   │       │   └── exception/VideoIntelligenceException.java
│   │       └── ses/                             ← AWS SES v2 adapter
│   │           ├── SesEmailService.java         ← SesV2Client; no template logic
│   │           ├── SesProperties.java
│   │           ├── SesConfig.java
│   │           └── exception/EmailException.java
│   │
│   └── resources/
│       ├── application.yml
│       ├── application-dev.yml
│       ├── application-prod.yml
│       ├── db/migration/
│       │   ├── (existing migrations V1–Vn)
│       │   ├── V{n+1}__platform_config.sql
│       │   ├── V{n+2}__security_shadow_accounts.sql
│       │   ├── V{n+3}__marketplace_coach_profiles.sql
│       │   ├── V{n+4}__booking_state_machine.sql
│       │   ├── V{n+5}__session_drill_library.sql
│       │   ├── V{n+6}__development_skill_stats.sql
│       │   ├── V{n+7}__video_pipeline.sql
│       │   ├── V{n+8}__payment_subscriptions.sql
│       │   ├── V{n+9}__messaging.sql
│       │   └── V{n+10}__admin_moderation.sql
│       └── i18n/
│           ├── messages.properties
│           ├── messages_en.properties
│           └── messages_de.properties
│
└── test/
    └── java/com/softropic/skillars/
        ├── config/
        │   └── PostgresContainerConfig.java     ← EXISTING
        ├── platform/
        │   ├── booking/
        │   │   ├── BaseBookingIT.java
        │   │   ├── api/BookingResourceIT.java
        │   │   ├── service/BookingServiceTest.java
        │   │   └── service/BookingStateMachineTest.java
        │   ├── session/
        │   │   ├── service/WrapUpServiceIT.java
        │   │   └── service/DrillLibraryServiceTest.java
        │   ├── development/
        │   │   ├── service/SluCalculationServiceIT.java ← Awaitility for async event
        │   │   └── api/PlayerSkillStatsResourceIT.java
        │   ├── video/
        │   │   ├── BaseVideoIT.java
        │   │   ├── api/VideoResourceIT.java
        │   │   ├── api/VideoWebhookResourceIT.java ← WireMock for Bunny.net webhook
        │   │   ├── service/VideoQuotaServiceTest.java
        │   │   └── service/VideoModerationServiceTest.java
        │   ├── payment/
        │   │   ├── service/StripeWebhookServiceIT.java ← WireMock for Stripe
        │   │   └── service/SessionPackServiceTest.java
        │   └── messaging/
        │       ├── service/GeminiModerationServiceTest.java ← WireMock for Gemini
        │       └── api/MessageResourceIT.java
        └── infrastructure/
            ├── bunny/BunnyTusServiceIT.java
            ├── arachnid/ArachnidShieldServiceIT.java
            └── videointel/VideoIntelligenceServiceIT.java

src/frontend/
├── src/
│   ├── api/
│   │   ├── auth.api.js          booking.api.js    marketplace.api.js
│   │   ├── session.api.js       development.api.js video.api.js
│   │   └── payment.api.js       messaging.api.js   config.api.js
│   ├── stores/
│   │   ├── auth.store.js        booking.store.js   marketplace.store.js
│   │   ├── session.store.js     development.store.js video.store.js
│   │   └── payment.store.js     messaging.store.js
│   ├── composables/
│   │   ├── useSseConnection.js  ← shared SSE with exponential backoff + polling fallback
│   │   ├── useAgePolicy.js      ← reads tier restrictions from config.api.js
│   │   ├── useQuotaDisplay.js   ← formats storage/bandwidth for display
│   │   └── useTimezone.js       ← Luxon pitch-timezone conversion
│   ├── components/
│   │   ├── common/
│   │   │   ├── GlassCard.vue
│   │   │   ├── BookingStateChip.vue    ← maps BookingStatusDto.value to colour + label
│   │   │   ├── SessionPackTracker.vue
│   │   │   └── ReliabilityIndicator.vue
│   │   ├── marketplace/
│   │   │   ├── CoachCard.vue
│   │   │   ├── CoachSearch.vue
│   │   │   └── VerificationBadge.vue
│   │   ├── session/
│   │   │   ├── ActiveSessionScreen.vue  ← full-screen takeover
│   │   │   ├── WrapUpSequence.vue       ← 4-step, 30-second flow
│   │   │   ├── SessionBuilder.vue
│   │   │   ├── DrillCard.vue
│   │   │   └── SessionDNAChart.vue
│   │   ├── development/
│   │   │   ├── SkillsRadarChart.vue     ← SVG + accessible <table>
│   │   │   └── PlayerTimeline.vue
│   │   ├── video/
│   │   │   ├── VideoStatusCard.vue      ← aria-live; SSE-reactive
│   │   │   └── VideoUploadFlow.vue      ← tus-js-client integration
│   │   └── messaging/
│   │       └── MessageThread.vue
│   ├── pages/
│   │   ├── marketplace/
│   │   │   ├── MarketplacePage.vue
│   │   │   └── CoachProfilePage.vue
│   │   ├── coach/
│   │   │   ├── CommandCenter.vue        ← 3-column desktop; revenue + schedule
│   │   │   ├── SessionBuilderPage.vue
│   │   │   ├── ClientRosterPage.vue
│   │   │   ├── DrillLibraryPage.vue
│   │   │   └── RevenuePage.vue
│   │   ├── parent/
│   │   │   ├── ParentPortalPage.vue
│   │   │   ├── DevelopmentDashboardPage.vue
│   │   │   └── VideoApprovalPage.vue
│   │   ├── player/
│   │   │   ├── LockerRoomPage.vue
│   │   │   └── MySkillsPage.vue
│   │   └── admin/
│   │       ├── ModerationQueuePage.vue
│   │       └── DisputeResolutionPage.vue
│   ├── router/index.js
│   ├── i18n/
│   │   ├── en/index.js
│   │   └── de/index.js
│   ├── css/
│   │   ├── app.scss
│   │   └── tokens/
│   │       ├── _colors.scss
│   │       └── _typography.scss
│   └── boot/
│       ├── axios.js
│       ├── i18n.js
│       └── sw-register.js         ← Web Push Service Worker registration
├── public/
│   └── sw.js                      ← Service Worker (Web Push handler)
└── quasar.config.js
```

---

### Architectural Boundaries

**API Boundary — what enters and exits each module:**
- Enters: DTO `record` types from `contract/`; authenticated `Principal`
- Exits: DTO `record` types, SSE events, `ErrorDto` on failure
- Never exits: JPA entities, raw Stripe/Bunny SDK types, internal enums

**Service Boundary — internal module contracts:**
- `BookingService.transition()` is the sole entry point for booking state changes
- `ConfigService` is the sole source of all configurable values
- `SluCalculationService` is the sole writer of `player_skill_stats`
- `VideoQuotaService` is the sole manager of quota reservations
- No module may inject a repository from another module

**Data Boundary — DB table ownership:**
- Each module's tables are exclusively owned by that module
- Cross-module data access via `ApplicationEvent` or REST API only — never by injecting another module's `@Repository`
- `player_skill_stats` rows are immutable once written (no UPDATE path exists)
- `platform_config` is read-only for all modules except `platform.admin`

---

### Integration Points

**Internal Communication (ApplicationEvents, AFTER_COMMIT):**

```
platform.booking.BookingService
  → BookingCompletedEvent → platform.development.SluCalculationService (bake SLUs)
  → BookingCompletedEvent → platform.payment.SessionPackService (deduct credit)
  → BookingDisputedEvent  → platform.admin.ModerationQueueService (add item)

platform.session.WrapUpService
  → SessionCompletedEvent → platform.development.SluCalculationService

platform.payment.SessionPackService
  → SessionPackExhaustedEvent → platform.video.VideoQuotaService (gate uploads)
  → SessionPackExhaustedEvent → platform.messaging.MessageService (gate access)

platform.video.VideoUploadService
  → VideoPublishedEvent → platform.messaging (parent notification for minors)

platform.security (account deletion)
  → AccountDeletionRequestedEvent → platform.video (purge cascade)
  → AccountDeletionRequestedEvent → platform.messaging (message purge)
  → AccountDeletionRequestedEvent → platform.development (timeline purge)
```

**External Integrations:**

| Service | Adapter | Direction | Protocol |
|---|---|---|---|
| Bunny.net Stream | `infrastructure.bunny` | outbound + inbound webhook | TUS upload; REST signed URL; HMAC-SHA256 webhook |
| Stripe Connect | `platform.payment` (direct SDK) | outbound + inbound webhook | Stripe Java SDK; Stripe-Signature webhook |
| Google Video Intel | `infrastructure.videointel` | outbound async | REST job; longrunning.Operations poll |
| Project Arachnid | `infrastructure.arachnid` | outbound sync | REST; hash submit → boolean |
| Gemini API | `platform.messaging` (direct SDK) | outbound sync | Gemini Java SDK; fail-closed |
| AWS SES v2 | `infrastructure.ses` | outbound | AWS SDK v2 SesV2Client |
| Web Push | frontend Service Worker | server → client | Web Push API |

**Key Data Flows:**

*Video Upload:*
```
Pre-flight (quota reservation + TUS URL) → Client uploads to Bunny.net →
Arachnid hash check (SCANNING) → Google Video Intel scan (SCANNING) →
PUBLISHED → SSE VideoStatusCard update → VideoPublishedEvent (minor: parent notified)
```

*Session Completion (Live Mode):*
```
Coach taps End → WrapUpService.save() → BookingService.transition(COMPLETED) →
[AFTER_COMMIT] SluCalculationService.bake() → player_skill_stats rows written →
PaymentService.deductCredit() → parent push notification
```

*Message (Minor Player):*
```
MessageService receives → Gemini classify →
  CLEAR: persist + SSE deliver
  FLAG: quarantine + ModerationQueueItem + parent alert
  UNAVAILABLE: quarantine (fail-closed)
```

---

### File Organisation Patterns

**Flyway Migrations:** Each module gets its own migration file(s), versioned in
implementation sequence order. All constraint names follow:
`fk_{table}_{ref}`, `idx_{table}_{col}`, `uq_{table}_{col}`.

**i18n Keys:** German (`messages_de.properties`) and English (`messages_en.properties`)
maintained in parallel. Module prefix on all keys: `booking.*`, `video.*`, `payment.*`.
All user-facing message changes require both files updated simultaneously.

**Environment Config:** Secrets injected via environment variables only.
`application-dev.yml` contains non-secret dev defaults.
`application-prod.yml` contains non-secret prod defaults.
No secret or production value committed to source control.

---

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
All technology choices are mutually compatible. Spring Boot 3.5 / Java 17 integrates
cleanly with Spring Data JPA, Spring Security, and Spring's `ApplicationEvent` mechanism.
The Stripe Java SDK, Gemini Java SDK, and AWS SDK v2 operate as independent dependencies
with no runtime conflicts. Quasar 2.16/Vue 3.5 frontend is fully decoupled from the
backend, consuming only REST + SSE endpoints.

**Pattern Consistency:**
`@TransactionalEventListener(phase = AFTER_COMMIT)` is used uniformly for all
cross-module async communication — no mixed patterns. Fail-closed moderation is
applied consistently across all three moderation layers (Arachnid, VideoIntel, Gemini).
`SELECT FOR UPDATE` is used exclusively for optimistic-to-pessimistic boundary cases
(quota reservation, credit deduction); all other entities use `@Version` for optimistic
locking. SSE + polling fallback is specified once in `useSseConnection.js` composable and
reused across booking, video, and messaging status displays.

**Structure Alignment:**
The project structure directly mirrors the module map. Every platform module has a
matching `pages/` group in the frontend. Integration points (events, webhook handlers,
SSE registries) are located in the owning module's `service/` layer.

---

### Requirements Coverage Validation ✅

**Functional Requirements Coverage:**

| FR Category | Coverage | Owning Location |
|---|---|---|
| FR-PLT-001 Config Layer | ✅ Full | `platform.config` + `platform_config` table |
| FR-MKT Marketplace | ✅ Full | `platform.marketplace` |
| FR-BKG Booking (14 states) | ✅ Full | `platform.booking.BookingService` |
| FR-SES Session Builder | ✅ Full | `platform.session` |
| FR-DRL Drill Library | ✅ Full | `platform.session.DrillLibraryService` |
| FR-DEV Development Intelligence | ✅ Full | `platform.development` |
| FR-POR Player Portal | ✅ Full | Frontend `pages/parent/` + `pages/player/` |
| FR-VID Video Module | ✅ Full | `platform.video` + 3 infrastructure adapters |
| FR-PAY Payments | ✅ Full | `platform.payment` + Stripe Connect Express |
| FR-MSG Messaging | ✅ Full | `platform.messaging` + Gemini moderation |
| FR-REV Reviews | ✅ Full | `platform.marketplace.ReviewService` |
| FR-ADM Admin | ✅ Full | `platform.admin` |
| FR-TSC Trust & Safety | ✅ Full | Cross-cutting: moderation chain + family isolation |
| FR-USR User Management | ✅ Full | `platform.security` (existing, extended) |

**Non-Functional Requirements Coverage:**

- **GDPR / EU Data Residency:** All infrastructure (AWS SES eu-west-1, Google Video Intel
  europe-west1, Bunny.net EU storage zone, PostgreSQL EU region) confirmed EU-only.
  Erasure cascade documented with 30-day physical deletion / 90-day backup purge.
- **Security:** JWT HttpOnly + SameSite=Strict; family isolation at Spring Security +
  repository layer; all webhooks verified by HMAC-SHA256. OWASP alignment explicit.
- **Performance:** SLU snapshot table eliminates historical joins. Signed URL TTL (2h)
  prevents token reuse. Video served via Bunny.net CDN, not through the application layer.
- **Scalability:** Modular monolith boundary definitions enable vertical extraction of
  high-traffic modules (video, marketplace) in a future phase without rewriting domain logic.
- **Child Safety:** Three-layer moderation stack fail-closed by design; minor player content
  requires parent approval before visibility; Gemini fail-closed on chat moderation for any
  minor-involving thread.

---

### Implementation Readiness Validation ✅

**Decision Completeness:**
All 8 critical decisions are documented with rationale and implementation detail.
Technology versions are specified. Integration provider choices are locked with alternatives
ruled out (Hive vs Google VideoIntel; Stripe Express vs Custom; SSE vs WebSocket).
All post-MVP deferrals are explicitly marked.

**Structure Completeness:**
Complete backend and frontend directory trees are defined with concrete Java class names
and Vue component names. Flyway migration sequence is mapped to modules. Test class names
follow the `*IT` / `*Test` naming pattern consistently.

**Pattern Completeness:**
14 conflict-prone areas identified and resolved. Naming conventions cover REST paths, YAML
namespaces, DB table names, Java class suffixes, ApplicationEvent names, error code enums,
SSE event types. Process patterns cover `@Transactional` boundaries, retry patterns,
fail-closed moderation, the streaming anti-pattern, and Stripe webhook idempotency.

---

### Gap Analysis Results

**No Critical Gaps.**

**Minor Gaps (resolvable at implementation start):**

1. **Existing module inventory:** `project-context.md` references existing modules
   `notification` and `tenant`. Implementer should confirm: (a) `platform.notification`
   is superseded by `infrastructure.ses` + Web Push — or retained as a thin orchestration
   layer; (b) `platform.tenant` remains unchanged and out of Skillars scope.

2. **Flyway version numbers:** Migration files use `V{n+1}` notation because the existing
   sequence endpoint is unknown. The first implementation story must resolve actual V
   numbers by inspecting the existing migration history.

3. **`platform_config` initial seed data:** The architecture defines the config key schema
   but the `V{n+1}__platform_config.sql` seed values for all tier entitlements, quota
   defaults, and commission rates should be defined as part of the first implementation
   story for `platform.config`.

**Nice-to-Have:**
- Sequence diagrams for the video upload and session completion flows would aid frontend
  developers implementing SSE state machines, but are not blocking.

---

### Validation Issues Addressed

No blocking issues found. Minor gaps documented above are logged as first-story concerns,
not architectural blockers.

---

### Architecture Completeness Checklist

**Requirements Analysis**

- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed (enterprise-grade: 14-state machine, 3-layer moderation, dual dev systems)
- [x] Technical constraints identified (GDPR EU-only, Spring Boot 3.5/Java 17, Quasar 2.16/Vue 3.5, PostgreSQL, no external broker at MVP)
- [x] Cross-cutting concerns mapped (10 concerns: JWT, family isolation, config layer, SLU immutability, quota reservation, moderation, contact sanitization, SSE, outbox, GDPR erasure)

**Architectural Decisions**

- [x] Critical decisions documented with versions
- [x] Technology stack fully specified (backend + frontend + all 7 external services)
- [x] Integration patterns defined (events, webhooks, SSE, TUS, Stripe Connect, Destination Charges)
- [x] Performance considerations addressed (snapshot table, CDN delivery, no streaming through app layer)

**Implementation Patterns**

- [x] Naming conventions established (REST paths, YAML, DB tables, Java suffixes, events, error codes, SSE types)
- [x] Structure patterns defined (state machine ownership, SLU boundary, ConfigService usage, parent_id contract, SSE registry)
- [x] Communication patterns specified (cross-module events, SSE, polling fallback, webhook idempotency)
- [x] Process patterns documented (@Transactional boundaries, retry, fail-closed, Stripe webhook, outbox)

**Project Structure**

- [x] Complete directory structure defined (backend + frontend, concrete class names)
- [x] Component boundaries established (API/service/data boundaries; no cross-module repository injection)
- [x] Integration points mapped (7 external services; internal event graph)
- [x] Requirements to structure mapping complete (all 14 FR categories → owning locations)

---

### Architecture Readiness Assessment

**Overall Status: READY FOR IMPLEMENTATION**

All 16 checklist items confirmed. No critical gaps. Minor gaps are first-story concerns
requiring minimal investigation, not architectural blockers.

**Confidence Level:** High

**Key Strengths:**
- Explicit, testable module boundaries prevent cross-module coupling drift
- Single-owner invariants (BookingService, SluCalculationService, ConfigService) eliminate competing-writer bugs
- Fail-closed moderation throughout the content pipeline is the correct default for a children's platform
- Infrastructure adapter pattern (zero platform imports) keeps all external services swappable
- Feature config layer eliminates code deployments for business rule changes

**Areas for Future Enhancement:**
- Extract `platform.video` and `platform.marketplace` as independent services when read traffic warrants horizontal scaling
- Add grooming-risk scoring (Phase 2) once production messaging data is available to calibrate the model
- Add call recording / transcription (Phase 2) with dedicated GDPR consent flow design
- Consider replacing internal `ApplicationEvent` with a lightweight broker (e.g., Redis Pub/Sub) if cross-service extraction occurs in Phase 2

---

### Implementation Handoff

**AI Agent Guidelines:**
- Follow all architectural decisions exactly as documented
- Use implementation patterns consistently across all components
- Respect project structure and boundaries defined in Step 6
- Refer to this document for all architectural questions — it is the authoritative source
- `BookingService.transition()` is the ONLY permitted path for booking state changes
- `ConfigService` is the ONLY permitted source for configurable business values
- Never write UPDATE statements against `player_skill_stats`
- Never inject a repository from module A into a service in module B

**First Implementation Priority:**
1. Confirm existing module inventory (`notification`, `tenant`) and resolve Flyway V sequence
2. Implement `platform.config` + seed data for all tier entitlements and quota defaults
3. Extend `platform.security` with shadow accounts, `AgePolicyService`, and `RefreshToken` rotation
4. Implement `platform.booking` state machine foundation — this unblocks all other modules
