---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - '_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/prd.md'
  - '_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/addendum.md'
  - '_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/reconcile-requirements-spec.md'
---

# javatemplate - Video Storage & Streaming Module Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for the Video Storage & Streaming Module, decomposing the requirements from the PRD, Addendum, and Reconciliation Report into implementable stories. No formal Architecture document exists for this module; technical requirements are derived from the PRD addendum (A2, A5, A6) and project-context.md conventions.

## Requirements Inventory

### Functional Requirements

**FR-1: Upload Initialization**
`VideoService.initializeUpload(ownerId, fileName, fileSizeBytes)` must invoke `QuotaProvider.check()` and reject if quota denied; returns signed upload credentials from the active provider on success; Upload Session created in PENDING status.

**FR-2: Quota Reservation**
After `QuotaProvider.check()` passes, call `QuotaProvider.reserve(ownerId, fileSizeBytes)`; store returned reservation handle on the Upload Session record.

**FR-3: Direct Provider Upload**
Video content MUST be uploaded by clients directly to the provider via signed URL; backend MUST NOT proxy video payloads under any circumstance.

**FR-4: Upload Confirmation**
Advance Upload Session to COMMITTED and Video to PROCESSING on either (a) valid provider webhook or (b) client confirmation call; both paths supported; idempotent — duplicate signals leave the Video in the same final state.

**FR-5: Upload Session Expiry**
Upload Sessions MUST expire automatically after configurable TTL; on expiry call `QuotaProvider.release(reservationHandle)` and mark session EXPIRED; a background job enforces expiry without requiring client action.

**FR-6: Upload Validation**
Validate file size, MIME type, and container format before creating an Upload Session; client-provided MIME types are advisory only; server-side validation is authoritative. Defaults: max 5 GB (`video.upload.max-bytes`), allowed MIME: video/mp4, video/quicktime, video/webm, video/x-msvideo (`video.upload.allowed-mime-types`), formats: MP4/MOV/WebM/AVI (`video.upload.allowed-formats`); all bounds configurable via properties.

**FR-7: Rate Limiting**
Upload initialization MUST be rate-limited per caller; rate limit values configurable via Spring properties.

**FR-8: Operational States**
Maintain one of the following Operational States for every Video: UPLOADING, PROCESSING, READY, FAILED, DELETED; current state readable by consuming apps via the service layer.

**FR-9: Access States**
Maintain one of the following Access States for every Video: ACTIVE, BLOCKED, ARCHIVED. BLOCKED may be set for any app-determined reason (admin/coach restriction, account suspension, moderation hook).

**FR-10: Playback Eligibility Gate**
A Video is playback-eligible ONLY when `operationalState == READY AND accessState == ACTIVE`; all other combinations return a denial response with the disqualifying state(s).

**FR-11: Terminal State Enforcement**
DELETED is a terminal state; reject any operation that would transition a DELETED Video to another Operational State.

**FR-12: FAILED Retry**
FAILED Videos may restart the upload flow via a new Upload Session on the existing Video record; Video `id` is stable across retry attempts; Video remains FAILED until new session is confirmed; no new Video entity is created.

**FR-13: Signed Playback Only**
All playback MUST use signed Playback Tokens or provider-secured mechanisms; unsigned public playback URLs MUST NOT be returned by any service path.

**FR-14: Playback Eligibility Check Before Token Issuance**
The module MUST verify the playback eligibility gate (FR-10) before issuing any Playback Token.

**FR-15: Playback Token Requirements**
Issued Playback Tokens MUST: expire automatically, be cryptographically signed, include an `issuedAt` timestamp, include a unique token identifier.

**FR-16: Playback Token Claims (SHOULD)**
Playback Tokens SHOULD include `userId`, `sessionId`, and `expiration` claims.

**FR-17: Token TTL**
Default Playback Token TTL is 15 minutes; maximum is 2 hours; both configurable via Spring properties; TTL validation occurs server-side before returning a provider playback URL.

**FR-18: Token Revocation**
Support invalidation of active Playback Tokens for a given viewer on: password change, account suspension, explicit logout, or security incident signal. Consuming app triggers via service call supplying `viewerId`. Tokens persisted as records in DB with `revokedAt` field; server-side validation checks `revokedAt` before issuing playback URL. After revocation, the module MUST block new token issuance for the affected viewer for a configurable revocation window (default 24 hours, property `app.video.playback.revocation-window-hours`). The consuming app must wait out this window — or configure it to zero to disable the block — before the viewer can receive new tokens.

**FR-19: Provider Adapter Interface**
Define `VideoProviderAdapter` interface in `infrastructure.video`; no code in `platform.video` may import or depend on a provider-specific SDK directly.

**FR-20: Required Adapter Operations**
Every Provider Adapter implementation MUST implement: `initializeUpload()`, `getAssetStatus()`, `generatePlaybackUrl()`, `deleteAsset()`, `verifyWebhook()`.

**FR-21: Optional Adapter Operations**
Provider Adapter implementations MAY implement: `archiveAsset()`, `restoreAsset()` — default: `throw UnsupportedOperationException`.

**FR-22: Provider Capability Baseline**
Every provider implementation MUST support: direct uploads, signed playback URLs, webhook events, adaptive streaming, asset deletion.

**FR-23: MVP Provider**
Bunny.net is the MVP provider implementation; provider selection configurable via `video.provider.active` Spring property; swapping requires only a property change and a compatible adapter implementation; no `platform.video` code changes required.

**FR-24: Webhook Processing**
Process provider events delivered via webhook; validate via `verifyWebhook()` before any state transition; unverified or malformed webhooks rejected without state changes.

**FR-25: Polling Fallback Reconciliation**
Run a Reconciliation Worker as a background job that polls provider asset status for Videos in non-terminal states; module MUST NOT rely solely on webhooks.

**FR-26: Idempotent Event Processing**
Webhook and polling-based event processing MUST be idempotent; re-delivery of the same event MUST NOT produce duplicate state transitions or duplicate records.

**FR-27: Reconciliation Rules**
Reconciliation Worker applies: (a) provider reports asset exists but local state missing → create recovery record, deny playback until Admin Layer review; (b) provider reports asset missing → mark Video FAILED.

**Implementation constraint:** The automated Reconciliation Worker iterates over Videos known to the local database. Fully orphaned assets — where a provider asset exists but no corresponding `Video` record was ever created locally — cannot be detected by the automated worker. Detection of such orphaned assets requires an explicit admin-triggered reconciliation that performs an inverse provider-asset-list scan. The automated worker applies rule (b) only; rule (a) is applied during admin-triggered reconciliation.

**FR-28: Reconciliation SLA**
Reconciliation Worker MUST complete a full cycle and resolve detected discrepancies within 5 minutes.

**FR-29: QuotaProvider Interface**
Define `QuotaProvider` interface in `platform.video.contract` with: `check(ownerId, requestedBytes)`, `reserve(ownerId, bytes)`, `commit(reservationHandle)`, `release(reservationHandle)`. Module fails fast at startup if no `QuotaProvider` bean is registered. `NoOpQuotaProvider` available as explicit opt-in (not a silent default).

**FR-30: QuotaProvider Concurrency Contract**
`QuotaProvider` contract documentation MUST explicitly state that implementations are responsible for concurrent-safe quota enforcement; the module orchestrates the call sequence but does not add supplementary locking.

**FR-31: Admin Service Layer**
Expose Admin service layer in `platform.video.service` covering: Video Access State overrides (ACTIVE, BLOCKED, DELETED), Upload Session inspection, Reconciliation Worker manual trigger.

**FR-32: Admin Resource Endpoint**
Expose base admin REST endpoint in `platform.video.api`; secured with `@PreAuthorize` using `SecurityConstants`; designed as extension point for consuming apps. End-user REST controllers are the consuming app's responsibility (out of scope).

**FR-33: Visibility Modes**
Track Visibility on every Video: PRIVATE, GROUP, UNLISTED. (Terminology standardized to GROUP, not TEAM.)

**FR-34: Visibility Enforcement Boundary**
Module stores and returns Visibility; does NOT gate playback on Visibility — only on Operational State and Access State (FR-10). Consuming apps must apply Visibility enforcement in their own layer.

**FR-35: Adaptive Streaming**
Module MUST support HLS streaming with adaptive bitrate playback via the active provider. Adaptive streaming is a required provider capability (FR-22); module enables and documents its use.

**FR-36: Optional Media Enhancements (SHOULD)**
Where the active provider supports them: thumbnail generation and preview scrubbing; subtitle tracks and chapter markers.

### NonFunctional Requirements

- NFR-1 (Performance): Upload initialization latency < 500ms p99
- NFR-2 (Performance): Playback token generation latency < 200ms p99
- NFR-3 (Availability): Upload service availability 99.9%
- NFR-4 (Availability): Playback service availability 99.95%
- NFR-5 (Reliability): Reconciliation Worker completes full cycle and resolves discrepancies ≤ 5 minutes
- NFR-6 (Security): All playback URLs MUST be signed or provider-secured; no unsigned public URLs through any service path
- NFR-7 (Security): All webhook payloads MUST have signatures validated before any state transition occurs
- NFR-8 (Security): Upload initialization MUST be rate-limited per caller
- NFR-9 (Security): Playback providers SHOULD enforce allowed origins/domains where the provider supports it
- NFR-10 (Security): Raw provider credentials and API keys MUST NOT appear in logs or API responses
- NFR-11 (Reliability): Background jobs MUST use dead-letter queues for failed events
- NFR-12 (Reliability): All background job processing MUST be retry-safe and idempotent
- NFR-13 (Scalability): Module MUST support horizontal scaling
- NFR-14 (Observability): Module services MUST use `@Observed(name = "...")` annotations per javatemplate convention

### Additional Requirements

**Module Structure (from project-context.md + addendum A2):**
- Domain layer: `com.softropic.skillars.platform.video.{api, service, repo, contract, config}` — owns business logic, JPA entities (Video, UploadSession, PlaybackToken), repositories, QuotaProvider interface, lifecycle schedulers
- Infrastructure layer: `com.softropic.skillars.infrastructure.video` — owns `VideoProviderAdapter` interface + Bunny.net implementation only; must be business-agnostic (no platform.* imports)
- All DTOs as Java `record` types; MapStruct for entity/DTO mapping; Lombok `@Getter`/`@Slf4j` on entities and services
- All schema changes via Flyway migrations only; no DDL in Java code
- `@PreAuthorize` on every REST endpoint using SecurityConstants; no unprotected endpoints
- `@Observed` on all resource methods for Micrometer/OTEL tracing

**Data Model (from addendum A5, visibility terminology standardized to GROUP):**
- `Video`: id (UUID), ownerId (String/UUID), provider (Enum), providerAssetId (String), operationalState (Enum: UPLOADING/PROCESSING/READY/FAILED/DELETED), accessState (Enum: ACTIVE/BLOCKED/ARCHIVED), title (String), description (String nullable), durationMs (Long), storageBytes (Long), visibility (Enum: PRIVATE/GROUP/UNLISTED), createdAt, updatedAt
- `UploadSession`: id (UUID), videoId (UUID FK → Video), providerUploadId (String), status (Enum: PENDING/COMMITTED/EXPIRED), reservedBytes (Long), reservationHandle (String), expiresAt (Timestamp), createdAt (Timestamp)
- `PlaybackToken`: id (UUID), videoId (UUID FK → Video), viewerId (String/UUID), expiresAt (Timestamp), revokedAt (Timestamp nullable), createdAt (Timestamp)

**Provider Adapter Interface (from addendum A6):**
```
interface VideoProviderAdapter {
    UploadCredentials initializeUpload(String fileName, long fileSizeBytes);
    AssetStatus getAssetStatus(String providerAssetId);
    SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims);
    void deleteAsset(String providerAssetId);
    WebhookEvent verifyWebhook(String payload, String signature);
    default void archiveAsset(String providerAssetId) { throw new UnsupportedOperationException(); }
    default void restoreAsset(String providerAssetId) { throw new UnsupportedOperationException(); }
}
```

**Background Jobs (from addendum A2):**
- Spring `@Scheduled` workers in `platform.video.service` for: Upload Session expiry + quota release, Reconciliation Worker polling, dead-letter job inspection
- Queue-backed workers with DLQ for webhook and reconciliation events

**Design Constraint — No teamId on Video (from addendum A7, GAP-3):**
- `teamId` is intentionally absent from the Video entity; consuming apps that need team-video association must supply their own linkage mechanism; this must be documented in stories touching video ownership

**Open Implementation Question (from addendum OQ-4):**
- Playback Token revocation storage: DB `revokedAt` column (mandated by FR-18) is the baseline; Redis may be added as an accelerator (e.g., revocation bloom filter) at implementation time

### UX Design Requirements

N/A — This is a backend platform module with no UI surface.

### FR Coverage Map

| Requirement | Epic | Brief |
|---|---|---|
| FR-1 | Epic 2 | VideoService.initializeUpload + QuotaProvider.check |
| FR-2 | Epic 2 | QuotaProvider.reserve + reservationHandle on UploadSession |
| FR-3 | Epic 2 | Direct upload URL returned; no proxying |
| FR-4 | Epics 2, 4 | Client confirmation (Epic 2 Story 2.3) + webhook confirmation (Epic 4 Story 4.1) → Video to PROCESSING |
| FR-5 | Epic 2 | Session expiry scheduler + QuotaProvider.release |
| FR-6 | Epic 2 | Validation chain (size, MIME, format) before session creation |
| FR-7 | Epic 2 | Rate limiting on initializeUpload per caller |
| FR-8 | Epic 3 | Video operational state machine (5 states) |
| FR-9 | Epic 3 | Video access state machine (3 states) |
| FR-10 | Epic 3 | Playback eligibility gate (READY AND ACTIVE) |
| FR-11 | Epic 3 | Terminal state enforcement for DELETED |
| FR-12 | Epic 3 | FAILED retry via new UploadSession on existing Video |
| FR-13 | Epic 3 | Signed Playback Tokens only; no unsigned URLs |
| FR-14 | Epic 3 | Eligibility gate checked before token issuance |
| FR-15 | Epic 3 | Token: expiry, signed, issuedAt, unique id |
| FR-16 | Epic 3 | Token SHOULD claims: userId, sessionId, expiration |
| FR-17 | Epic 3 | Configurable TTL (default 15 min, max 2 hours) |
| FR-18 | Epic 3 | Token revocation via revokedAt; server-side validation |
| FR-19 | Epic 1 | VideoProviderAdapter interface in infrastructure.video |
| FR-20 | Epic 1 | Bunny.net implements all 5 required operations |
| FR-21 | Epic 1 | Optional archiveAsset/restoreAsset with default UnsupportedOperationException |
| FR-22 | Epic 1 | Provider capability baseline verified for Bunny.net |
| FR-23 | Epic 1 | Bunny.net as MVP; provider selected via video.provider.active |
| FR-24 | Epic 4 | Webhook pipeline: verifyWebhook → idempotent state transition |
| FR-25 | Epic 4 | Reconciliation Worker polling non-terminal Videos |
| FR-26 | Epic 4 | Idempotent event processing; no duplicate state transitions |
| FR-27 | Epic 4 | Recovery record on orphaned asset; FAILED on missing asset |
| FR-28 | Epic 4 | Reconciliation cycle ≤ 5 minutes |
| FR-29 | Epic 1 | QuotaProvider interface + NoOpQuotaProvider (explicit opt-in) |
| FR-30 | Epic 1 | QuotaProvider concurrency contract documentation |
| FR-31 | Epic 5 | Admin service: state overrides, session inspection, manual reconcile |
| FR-32 | Epic 5 | Admin REST endpoint with @PreAuthorize |
| FR-33 | Epic 1 | Visibility field (PRIVATE/GROUP/UNLISTED) on Video entity |
| FR-34 | Epic 1 | Enforcement boundary documented; module does not gate on Visibility |
| FR-35 | Epic 3 | HLS + adaptive bitrate via Bunny.net generatePlaybackUrl |
| FR-36 | Epic 5 | Optional: thumbnails, scrubbing, subtitles, chapters where provider supports |
| NFR-1 | Epic 2 | Upload init latency < 500ms p99 — implementation constraint |
| NFR-2 | Epic 3 | Playback token generation < 200ms p99 — implementation constraint |
| NFR-3 | Epic 4 | Upload availability 99.9% — reliability via reconciliation + DLQ |
| NFR-4 | Epic 4 | Playback availability 99.95% — reliability via reconciliation + DLQ |
| NFR-5 | Epic 4 | Reconciliation cycle ≤ 5 minutes (FR-28) |
| NFR-6 | Epics 1,3 | All playback URLs signed — enforced in provider adapter + playback service |
| NFR-7 | Epic 4 | Webhook signature validation before state transition |
| NFR-8 | Epic 2 | Rate limiting on upload initialization (FR-7) |
| NFR-9 | Epics 1,3 | Provider allowed origins/domains where supported |
| NFR-10 | Throughout | Raw credentials never in logs — enforced in each epic's stories |
| NFR-11 | Epic 4 | Dead-letter queues for failed background job events |
| NFR-12 | Epic 4 | Idempotent background job processing (FR-26) |
| NFR-13 | Epic 4 | Horizontal scaling via SKIP LOCKED |
| NFR-14 | Epic 5 | @Observed annotations on all service/resource methods |

## Epic List

### Epic 1: Module Foundation, Bunny.net Adapter & Integration Contract
Establishes the module skeleton, database schema, core contracts, and the complete Bunny.net provider adapter so that all subsequent epics have a stable, compiling foundation with a working provider integration.
**Delivers:** The module compiles, connects to Bunny.net (all required operations functional and unit-tested via WireMock), `QuotaProvider` and `VideoProviderAdapter` contracts are established and documented, all 3 Flyway-managed tables exist, consuming apps can register a `QuotaProvider` bean and wire the module.
**FRs covered:** FR-19, FR-20, FR-21, FR-22, FR-23, FR-29, FR-30, FR-33, FR-34

### Epic 2: Video Upload Pipeline
Delivers the complete upload flow: validation, quota lifecycle, Upload Session management, direct-to-Bunny.net upload URL, session expiry with quota release, rate limiting, and upload confirmation via both webhook and client signal.
**Delivers:** `VideoService.initializeUpload()` is fully functional — apps can accept video uploads from end users, end users upload directly to Bunny.net, confirmation advances Video to PROCESSING, expired sessions release quota automatically.
**FRs covered:** FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7

### Epic 3: Video Lifecycle & Playback Authorization
Delivers the complete video state machine, playback eligibility gating, signed Playback Token issuance with TTL, token revocation, and the FAILED-state retry flow.
**Delivers:** `PlaybackService.authorizePlayback()` is fully functional — tokens are issued only for eligible videos (`READY AND ACTIVE`), revoked on security events, denied with clear state diagnostics for ineligible videos, HLS playback URL served via Bunny.net. Apps can manage video access states and retry failed uploads.
**FRs covered:** FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, FR-14, FR-15, FR-16, FR-17, FR-18, FR-35

### Epic 4: Processing Reliability & Reconciliation
Delivers the dual-track processing pipeline: idempotent webhook event handling, Reconciliation Worker with `SKIP LOCKED` for horizontal safety, dead-letter queue for permanently failed events, and recovery record creation.
**Delivers:** The module is fault-tolerant. Missed webhooks are detected and recovered within 5 minutes; processing events are idempotent across re-deliveries; the system scales horizontally without duplicate state transitions.
**FRs covered:** FR-24, FR-25, FR-26, FR-27, FR-28 | NFRs: NFR-5, NFR-11, NFR-12, NFR-13

### Epic 5: Admin Layer, Observability & Production Readiness
Delivers the admin service layer (Access State overrides, Session inspection, manual Reconciliation trigger), the base admin REST endpoint, `@Observed` instrumentation across all services, and optional provider-dependent media enhancements.
**Delivers:** Operators have full visibility and control over videos in production; the observability pipeline is active; the module is production-ready and explorable by consuming app admin tooling.
**FRs covered:** FR-31, FR-32, FR-36 | NFRs: NFR-14

---

## Epic 1: Module Foundation, Bunny.net Adapter & Integration Contract

Establishes the module skeleton, database schema, core contracts, and the complete Bunny.net provider adapter so that all subsequent epics have a stable, compiling foundation with a working provider integration.

### Story 1.1: Module Scaffold, Database Schema & Configuration

As a developer integrating the Video module into a javatemplate application,
I want the module's package structure, database schema, configuration model, exception hierarchy, error handling, and test base class in place,
So that all subsequent video stories have a consistent, compiling foundation to build on.

**Acceptance Criteria:**

**Given** the project builds cleanly before this story
**When** the story is implemented
**Then** the following package structure exists: `com.softropic.skillars.platform.video.{api, service, repo, contract, contract/exception, contract/event, config}` and `com.softropic.skillars.infrastructure.video`
**And** `VideoProperties` is annotated `@ConfigurationProperties(prefix = "app.video")` and bound in `VideoConfig`
**And** `app.video.*` keys are present in `application.yml` with sensible defaults: `provider` (default: `bunny`), `upload.max-bytes` (5368709120 = 5 GB), `upload.allowed-mime-types`, `upload.allowed-formats`, `upload.session-ttl-minutes`, `upload.rate-limit.requests-per-minute`, `playback.token-ttl-minutes` (15), `playback.token-max-ttl-minutes` (120), `reconciliation.fixed-delay-ms`, `reconciliation.batch-size`, `bunny.api-key`, `bunny.library-id`, `bunny.cdn-hostname`

**Given** no video tables exist
**When** Flyway runs `V15__video_schema.sql`
**Then** table `videos` is created with columns: `id` UUID PK, `owner_id` VARCHAR NOT NULL, `provider` VARCHAR NOT NULL, `provider_asset_id` VARCHAR, `operational_state` VARCHAR NOT NULL, `access_state` VARCHAR NOT NULL, `title` VARCHAR NOT NULL, `description` TEXT nullable, `duration_ms` BIGINT nullable, `storage_bytes` BIGINT nullable, `visibility` VARCHAR NOT NULL CHECK (`visibility` IN ('PRIVATE', 'GROUP', 'UNLISTED')), `created_at` TIMESTAMP NOT NULL, `updated_at` TIMESTAMP NOT NULL
**And** `V16__upload_sessions.sql` creates `upload_sessions`: `id` UUID PK, `video_id` UUID NOT NULL FK → videos, `provider_upload_id` VARCHAR, `status` VARCHAR NOT NULL CHECK (`status` IN ('PENDING', 'COMMITTED', 'EXPIRED')), `reserved_bytes` BIGINT NOT NULL, `reservation_handle` VARCHAR, `expires_at` TIMESTAMP NOT NULL, `created_at` TIMESTAMP NOT NULL; index on `(status, expires_at)` for expiry scheduler queries
**And** `V17__playback_tokens.sql` creates `playback_tokens`: `id` UUID PK, `video_id` UUID NOT NULL FK → videos, `viewer_id` VARCHAR NOT NULL, `expires_at` TIMESTAMP NOT NULL, `revoked_at` TIMESTAMP nullable, `created_at` TIMESTAMP NOT NULL; index on `(viewer_id, revoked_at)` for revocation lookup

**Given** a JPA context is available
**When** the entities are loaded
**Then** `Video`, `UploadSession`, and `PlaybackToken` are valid `@Entity` classes with Lombok `@Getter`
**And** `VideoRepository`, `UploadSessionRepository`, and `PlaybackTokenRepository` are Spring Data repositories

**Given** a video module exception is thrown during a request
**When** it propagates to the API layer
**Then** `@ExceptionHandler` methods in `VideoApiAdvice` return the correct `ErrorDto` + HTTP status for each of: `VideoNotFoundException` → 404, `VideoValidationException` → 422, `QuotaExceededException` → 429, `PlaybackDeniedException` → 403, `VideoProviderException` → 502, `VideoSessionExpiredException` → 410, `TerminalStateViolationException` → 409
**And** `VideoErrorCode` implements `ErrorCode` with values: `VIDEO_NOT_FOUND`, `VALIDATION_FAILED`, `QUOTA_EXCEEDED`, `PLAYBACK_DENIED`, `PROVIDER_ERROR`, `SESSION_EXPIRED`, `TERMINAL_STATE_VIOLATION`

**Given** the i18n message files exist
**When** a video error key is looked up
**Then** `video.notFound`, `video.validationFailed`, `video.quotaExceeded`, `video.playbackDenied`, `video.providerError`, `video.sessionExpired`, `video.terminalStateViolation` are present in `messages.properties`, `messages_en.properties`, and `messages_fr.properties`

**Given** `BaseVideoIT` is defined as a `@SpringBootTest` + `@Testcontainers` base class
**When** any integration test class extends it
**Then** the PostgreSQL Testcontainers container (reusing `PostgresContainerConfig` from the existing test infrastructure) and a WireMock server for Bunny.net API calls are available and wired into the application context
**And** WireMock is configured to stub Bunny.net's base URL via `@DynamicPropertySource` overriding `app.video.bunny.*` endpoint properties

### Story 1.2: QuotaProvider Interface & Integration Contract

As a developer building a consuming application on javatemplate,
I want a well-defined `QuotaProvider` interface with clear concurrency contract documentation and fail-fast wiring validation,
So that I can implement quota enforcement for my specific use case without modifying the Video module.

**Acceptance Criteria:**

**Given** the module scaffold from Story 1.1 is in place
**When** `QuotaProvider` is defined in `platform.video.contract`
**Then** it declares: `boolean check(String ownerId, long requestedBytes)`, `String reserve(String ownerId, long bytes)`, `void commit(String reservationHandle)`, `void release(String reservationHandle)`
**And** the interface Javadoc explicitly states: "Implementations are responsible for concurrent-safe quota enforcement. The module orchestrates the check → reserve → commit/release sequence but does not add supplementary locking. Recommended approaches: transactional reservation, atomic compare-and-swap (UPDATE quota SET used = used + ? WHERE used + ? <= limit), or pessimistic SELECT FOR UPDATE."

**Given** no `QuotaProvider` bean is registered in the application context
**When** the application starts up
**Then** startup fails with a clear error message indicating a `QuotaProvider` bean is required; the module does not start silently with a no-op fallback

**Given** a consuming app that does not need quota enforcement
**When** they explicitly declare `@Bean QuotaProvider quotaProvider() { return new NoOpQuotaProvider(); }` in their configuration
**Then** `NoOpQuotaProvider.check()` always returns `true`, `reserve()` returns a non-null no-op handle string, `commit()` and `release()` complete without error
**And** `NoOpQuotaProvider` is not auto-configured or auto-registered by the module itself

**Given** the integration contract is documented
**When** a developer reads `platform.video.contract` package-info.java
**Then** it explicitly states: (1) `teamId` is absent from the `Video` entity — consuming apps that need team-video association must supply their own linkage; (2) `Visibility` (PRIVATE/GROUP/UNLISTED) is stored by the module but enforcement against group membership is the consuming app's responsibility; (3) end-user REST controllers for video operations are the consuming app's responsibility — the module provides the service layer only

**Given** unit tests for `QuotaProvider` wiring
**When** they run
**Then** a test verifies `NoOpQuotaProvider` satisfies all four method contracts; a separate test (using `@SpringBootTest` without a `QuotaProvider` bean) verifies startup fails as expected

### Story 1.3: VideoProviderAdapter Interface & Bunny.net Implementation

As a developer building upload and playback features,
I want a provider-agnostic `VideoProviderAdapter` interface backed by a working Bunny.net implementation,
So that all video operations can delegate to a consistent provider contract without coupling to Bunny.net's specific HTTP API.

**Acceptance Criteria:**

**Given** the module scaffold from Story 1.1 is in place
**When** `VideoProviderAdapter` is defined in `infrastructure.video`
**Then** it declares transport types: `UploadCredentials` (record: providerUploadId, signedUploadUrl), `AssetStatus` (enum: UPLOADING, PROCESSING, READY, FAILED, DELETED), `SignedPlaybackUrl` (record: url, expiresAt), `PlaybackTokenClaims` (record: viewerId, expiresAt), `WebhookEvent` (record: eventType, providerAssetId, timestamp)
**And** required methods: `UploadCredentials initializeUpload(String fileName, long fileSizeBytes)`, `AssetStatus getAssetStatus(String providerAssetId)`, `SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims)`, `void deleteAsset(String providerAssetId)`, `WebhookEvent verifyWebhook(String payload, String signature)`
**And** optional default methods: `default void archiveAsset(String providerAssetId) { throw new UnsupportedOperationException("archiveAsset not supported"); }` and `default void restoreAsset(String providerAssetId) { throw new UnsupportedOperationException("restoreAsset not supported"); }`
**And** no Bunny.net SDK types, HTTP client types, or infrastructure-specific imports appear in the interface

**Given** `app.video.provider=bunny` is configured
**When** `VideoProviderConfig` initializes beans
**Then** `BunnyVideoProviderAdapter` is wired as the active `VideoProviderAdapter` bean
**And** it is configured with `app.video.bunny.api-key`, `app.video.bunny.library-id`, and `app.video.bunny.cdn-hostname` injected via `BunnyProperties` — values are never hardcoded or logged in plaintext

**Given** `BunnyVideoProviderAdapter.initializeUpload(fileName, fileSizeBytes)` is called
**When** it executes
**Then** it calls `POST https://video.bunnycdn.com/library/{libraryId}/videos` with the configured API key header
**And** returns `UploadCredentials` with the Bunny.net `videoId` as `providerUploadId` and the TUS upload URL as `signedUploadUrl`

**Given** `BunnyVideoProviderAdapter.verifyWebhook(payload, signature)` is called with a valid Bunny.net HMAC-SHA256 signature
**When** it executes
**Then** it validates the signature using the configured `bunny.api-key` as the signing secret
**And** returns a parsed `WebhookEvent` with `eventType`, `providerAssetId`, and `timestamp`
**And** if the signature is invalid, throws `VideoProviderException` with `VideoErrorCode.PROVIDER_ERROR` — the raw payload is never logged

**Given** `BunnyVideoProviderAdapter.generatePlaybackUrl(providerAssetId, claims)` is called
**When** it executes
**Then** it returns a `SignedPlaybackUrl` whose `url` points to the HLS manifest (`https://{cdn-hostname}/{providerAssetId}/playlist.m3u8`) with a Bunny.net CDN token query parameter valid until `claims.expiresAt`
**And** the URL is always signed — unsigned public URLs are never returned (NFR-6)

**Given** WireMock stubs for all Bunny.net API endpoints
**When** `BunnyVideoProviderAdapterTest` (unit test with WireMock) runs
**Then** it covers: `initializeUpload` stubs a 200 response and verifies returned `UploadCredentials`; `getAssetStatus` maps Bunny.net status integers to `AssetStatus` enum values; `generatePlaybackUrl` produces a correctly signed HLS URL; `deleteAsset` fires the correct DELETE request; `verifyWebhook` accepts a valid HMAC signature and rejects an invalid one with `VideoProviderException`

---

## Epic 2: Video Upload Pipeline

Delivers the complete upload flow: validation, quota lifecycle, Upload Session management, direct-to-Bunny.net upload URL, session expiry with quota release, rate limiting, and upload confirmation via client signal.

**Note:** Webhook-based confirmation is deferred to Epic 4 (Processing Reliability). Client-side confirmation is sufficient per FR-4 — "both paths are supported; either is sufficient alone."

### Story 2.1: Upload Validation Chain

As a developer using the Video module,
I want a pluggable validation chain that enforces file size, MIME type, and container format rules before any upload is initialized,
So that invalid video files are rejected before any provider call or quota reservation is made.

**Acceptance Criteria:**

**Given** the module foundation from Epic 1 is in place
**When** `VideoValidationChain` is invoked with an `UploadValidationRequest`
**Then** it executes each `VideoValidationStep` in registration order, stopping on the first failure
**And** a failed step throws `VideoValidationException` with `VideoErrorCode.VALIDATION_FAILED` and a descriptive message

**Given** a file declared with `fileSizeBytes` exceeding `app.video.upload.max-bytes` (default 5 GB)
**When** `FileSizeValidationStep` runs
**Then** `VideoValidationException` is thrown before any quota check or provider call

**Given** a file declared with a MIME type not present in `app.video.upload.allowed-mime-types`
**When** `MimeTypeValidationStep` runs
**Then** `VideoValidationException` is thrown with a message identifying the rejected MIME type
**And** the client-provided MIME type is treated as advisory — the step validates the declared value against the configured allowlist; it does not trust it as authoritative

**Given** a file declared with a container format not present in `app.video.upload.allowed-formats`
**When** `FormatValidationStep` runs
**Then** `VideoValidationException` is thrown

**Given** the `VideoValidationStep` interface is defined
**When** a developer adds a new step (e.g., virus scan, custom content policy)
**Then** implementing `VideoValidationStep` and registering it as a Spring bean is sufficient to include it in the chain automatically

**Given** unit tests for the validation chain
**When** `VideoValidationChainTest` runs
**Then** it covers: each step passes valid input; each step rejects invalid input with the correct error code; chain halts on the first failing step; chain passes when all steps pass; steps execute in declared order

### Story 2.2: Upload Initialization & Quota Management

As a consuming application,
I want to call `VideoService.initializeUpload()` to get signed Bunny.net upload credentials with quota safety and rate limiting enforced,
So that end users can upload videos directly to Bunny.net without routing binary payloads through the backend.

**Acceptance Criteria:**

**Given** a valid `InitializeUploadRequest` (`ownerId`, `fileName`, `fileSizeBytes`)
**When** `VideoService.initializeUpload(ownerId, fileName, fileSizeBytes)` is called
**Then** `VideoValidationChain` runs first — a validation failure throws `VideoValidationException` (HTTP 422) before any quota or provider call
**And** `QuotaProvider.check(ownerId, fileSizeBytes)` is called — if it returns `false`, `QuotaExceededException` is thrown (HTTP 429) before any reservation or provider call
**And** `QuotaProvider.reserve(ownerId, fileSizeBytes)` is called and the returned `reservationHandle` is stored on the `UploadSession` record
**And** a `Video` record is created in `operationalState = UPLOADING`, `accessState = ACTIVE`, with the supplied `ownerId`, `fileName` (as `title`), and `visibility = PRIVATE` (default)
**And** an `UploadSession` record is created in `status = PENDING` with `expiresAt = now + app.video.upload.session-ttl-minutes`, `reservedBytes = fileSizeBytes`, and the `reservationHandle`
**And** `VideoProviderAdapter.initializeUpload(fileName, fileSizeBytes)` is called to obtain `UploadCredentials` from Bunny.net
**And** returns `InitializeUploadResponse`: `videoId`, `uploadSessionId`, `providerUploadId`, `signedUploadUrl`, `expiresAt`
**And** the `signedUploadUrl` is the Bunny.net TUS upload URL — no video bytes flow through the backend (FR-3)

**Given** the call sequence fails after `QuotaProvider.reserve()` but before the `Video`/`UploadSession` DB insert commits
**When** the exception propagates
**Then** `QuotaProvider.release(reservationHandle)` is called in a `finally` block to prevent a stranded reservation

**Given** a caller exceeds `app.video.upload.rate-limit.requests-per-minute` for a given `ownerId`
**When** `VideoService.initializeUpload()` is called
**Then** the request is rejected before validation or quota checks with HTTP 429

**Given** `VideoProviderAdapter.initializeUpload()` throws a `VideoProviderException` after the `Video` and `UploadSession` records have already been committed to the database
**When** the exception propagates out of `VideoService.initializeUpload()`
**Then** `QuotaProvider.release(reservationHandle)` is called in the `finally` block
**And** the caller receives HTTP 502 with `VideoErrorCode.PROVIDER_ERROR`
**And** the orphaned PENDING `UploadSession` and UPLOADING `Video` records remain in the database and will be cleaned up by the expiry scheduler on its next cycle

**Given** an integration test extending `BaseVideoIT` that runs `initializeUpload()` 100 times with WireMock configured to respond within 50ms
**When** all calls complete
**Then** 99% of calls complete within 500ms as measured by test execution timing (NFR-1)

**Given** an integration test extending `BaseVideoIT`
**When** it exercises the full initialization flow with WireMock stubbing Bunny.net's create-video endpoint
**Then** a `Video` record and `UploadSession` record are persisted with correct state and timestamps
**And** `QuotaProvider` methods are called in the correct sequence: `check` → `reserve`
**And** the returned `signedUploadUrl` matches the WireMock-stubbed Bunny.net TUS URL

### Story 2.3: Upload Confirmation & Session Expiry

As a consuming application,
I want to confirm that an upload succeeded so the Video advances to PROCESSING and quota is committed, and I want abandoned sessions to expire automatically and release their quota reservations,
So that the upload pipeline completes reliably and quota reservations never become permanently stranded.

**Acceptance Criteria:**

**Given** a client has successfully uploaded a video to Bunny.net using the URL from Story 2.2
**When** `VideoService.confirmUpload(videoId)` is called
**Then** it verifies the `UploadSession` for this video has `status = PENDING` — if `EXPIRED`, throws `VideoSessionExpiredException` (HTTP 410)
**And** within a single `@Transactional` block: sets `UploadSession.status = COMMITTED`, sets `Video.operationalState = PROCESSING`, calls `QuotaProvider.commit(reservationHandle)`
**And** returns `ConfirmUploadResponse`: `videoId`, `operationalState` (`PROCESSING`)

**Given** `VideoService.confirmUpload(videoId)` is called a second time for a video already in `PROCESSING`
**When** the duplicate confirmation arrives
**Then** an idempotent check detects `UploadSession.status = COMMITTED` and returns the existing `ConfirmUploadResponse` without re-calling `QuotaProvider.commit()`

**Given** `app.video.upload.session-ttl-minutes` has elapsed since an `UploadSession` was created without confirmation
**When** `UploadSessionExpiryScheduler` fires on its `@Scheduled(fixedDelay)` interval
**Then** it queries `upload_sessions WHERE status = 'PENDING' AND expires_at < NOW()` using `SELECT … FOR UPDATE SKIP LOCKED` (safe for multi-node deployment)
**And** for each expired session: calls `QuotaProvider.release(reservationHandle)` OUTSIDE any `@Transactional` boundary; then within a `@Transactional` block: calls `VideoLifecycleService.transitionOperationalState(videoId, FAILED)` (UPLOADING → FAILED transition) and sets `UploadSession.status = EXPIRED`
**And** if `QuotaProvider.release()` throws, the session is skipped (not marked expired) and will be retried on the next scheduler run

**Given** a `Video` record exists but has no associated `UploadSession` record
**When** `VideoService.confirmUpload(videoId)` is called
**Then** `VideoValidationException` (HTTP 422) is thrown with a message indicating no active upload session exists for this video
**And** no NullPointerException or HTTP 500 is produced

**Given** a `Video` record does not exist for the supplied `videoId`
**When** `VideoService.confirmUpload(videoId)` is called
**Then** `VideoNotFoundException` is returned (HTTP 404)

**Given** an integration test extending `BaseVideoIT`
**When** the full upload + confirmation flow is exercised
**Then** `confirmUpload()` transitions `Video` to `PROCESSING` and `UploadSession` to `COMMITTED`; a duplicate call returns the same response without re-committing quota; after simulating TTL expiry, `UploadSessionExpiryScheduler.processExpired()` marks sessions `EXPIRED`, sets affected Videos to `FAILED`, and calls `QuotaProvider.release()`

---

## Epic 3: Video Lifecycle & Playback Authorization

Delivers the complete video state machine, playback eligibility gating, signed Playback Token issuance with TTL, token revocation, and the FAILED-state retry flow.

### Story 3.1: Video State Machine & FAILED Retry

As a consuming application,
I want a fully enforced video state machine with two orthogonal state dimensions and support for restarting failed uploads on the same video record,
So that I can reliably manage video lifecycle and recover failed uploads without creating new video entities or breaking downstream references.

**Acceptance Criteria:**

**Given** a `Video` record exists
**When** `VideoLifecycleService.transitionOperationalState(videoId, newState)` is called
**Then** the following transitions are valid: UPLOADING → PROCESSING, UPLOADING → FAILED (expiry path), PROCESSING → READY, PROCESSING → FAILED, FAILED → UPLOADING (retry path)
**And** any transition targeting a `DELETED` video throws `TerminalStateViolationException` (HTTP 409)
**And** any invalid transition (e.g., READY → UPLOADING) throws `TerminalStateViolationException`

**Given** `VideoLifecycleService.setAccessState(videoId, newAccessState)` is called
**When** the video is in `DELETED` operational state
**Then** `TerminalStateViolationException` (HTTP 409) is thrown — DELETED videos cannot have access state modified
**And** for non-DELETED videos, access state transitions between ACTIVE, BLOCKED, and ARCHIVED are all permitted

**Given** a video with any combination of operational and access states
**When** `VideoLifecycleService.isPlaybackEligible(videoId)` is called
**Then** it returns `true` only when `operationalState == READY AND accessState == ACTIVE`
**And** for all other state combinations it returns `false` — the calling service is responsible for building the denial response with the disqualifying state(s)

**Given** a `Video` in `operationalState = FAILED`
**When** `VideoService.retryUpload(videoId, ownerId, fileSizeBytes)` is called
**Then** `VideoValidationChain` runs first; a validation failure throws `VideoValidationException` (HTTP 422)
**And** `QuotaProvider.check(ownerId, fileSizeBytes)` is invoked; `QuotaExceededException` (HTTP 429) if denied
**And** `QuotaProvider.reserve(ownerId, fileSizeBytes)` is invoked and a new `UploadSession` is created in `PENDING` status on the **existing** `Video` record — no new `Video` entity is created, the `videoId` is unchanged
**And** `VideoProviderAdapter.initializeUpload()` is called to obtain new upload credentials from Bunny.net
**And** returns `InitializeUploadResponse` with the same `videoId`, new `uploadSessionId`, and new `signedUploadUrl`
**And** `Video.operationalState` remains `FAILED` until the new session is confirmed via `confirmUpload()` (Story 2.3), at which point it transitions to PROCESSING

**Given** `retryUpload()` is called on a video NOT in `FAILED` state
**When** the precondition check runs
**Then** `VideoValidationException` (HTTP 422) is thrown with a message stating retry is only permitted for FAILED videos

**Given** unit tests for `VideoLifecycleService`
**When** they run
**Then** they cover: all valid transitions succeed (including UPLOADING → FAILED expiry path); all invalid transitions from DELETED throw `TerminalStateViolationException`; invalid non-DELETED transitions throw `TerminalStateViolationException`; `isPlaybackEligible` returns false for all combinations where operationalState != READY or accessState != ACTIVE; retry on a non-FAILED video throws correctly

### Story 3.2: Playback Token Issuance

As a consuming application,
I want to call `PlaybackService.authorizePlayback()` to obtain a signed, time-bounded HLS playback URL for an eligible video,
So that end users can stream video directly from Bunny.net's CDN with cryptographically signed, expiring credentials.

**Acceptance Criteria:**

**Given** a `videoId` and `viewerId`
**When** `PlaybackService.authorizePlayback(videoId, viewerId)` is called
**Then** `VideoLifecycleService.isPlaybackEligible(videoId)` is checked first — if `false`, `PlaybackDeniedException` (HTTP 403) is thrown with a response body containing both `operationalState` and `accessState` as denial reason
**And** if eligible, a `PlaybackToken` record is created: `id` (UUID = token identifier `jti`), `videoId`, `viewerId`, `expiresAt = now + app.video.playback.token-ttl-minutes`, `revokedAt = null`, `createdAt = now`
**And** `VideoProviderAdapter.generatePlaybackUrl(providerAssetId, PlaybackTokenClaims{viewerId, expiresAt})` is called to obtain a signed Bunny.net HLS URL
**And** the `PlaybackToken` is cryptographically signed as a JWT (HMAC-SHA256 with a configured signing secret) containing claims: `jti` (token UUID), `iat` (issuedAt epoch), `exp` (expiry epoch), `sub` (viewerId), `vid` (videoId)
**And** returns `PlaybackAuthorizationResponse`: `token` (signed JWT), `playbackUrl` (Bunny.net signed HLS manifest URL), `expiresAt`
**And** unsigned public playback URLs are never returned through any code path (NFR-6)

**Given** `app.video.playback.token-ttl-minutes` is configured to a value exceeding `app.video.playback.token-max-ttl-minutes` (120 minutes default)
**When** `authorizePlayback()` is called
**Then** the effective TTL used for `expiresAt` is capped at `token-max-ttl-minutes`

**Given** `videoId` does not exist
**When** `authorizePlayback()` is called
**Then** `VideoNotFoundException` (HTTP 404) is thrown

**Given** an integration test extending `BaseVideoIT` that runs `authorizePlayback()` 100 times for a pre-seeded `READY` + `ACTIVE` video with WireMock configured to respond within 20ms
**When** all calls complete
**Then** 99% of calls complete within 200ms as measured by test execution timing (NFR-2)

**Given** an integration test extending `BaseVideoIT`
**When** `authorizePlayback()` is called for a `Video` seeded directly in `READY` + `ACTIVE` state
**Then** a `PlaybackToken` record is persisted with correct `viewerId`, `videoId`, and `expiresAt`
**And** the returned JWT decodes to the expected claims
**And** the returned `playbackUrl` is a signed HLS manifest URL matching the WireMock-stubbed Bunny.net CDN response
**And** calling `authorizePlayback()` for a video in PROCESSING state returns HTTP 403 with the disqualifying `operationalState`

### Story 3.3: Playback Token Revocation

As a consuming application,
I want to revoke all active Playback Tokens for a viewer on security events, and validate individual tokens against the revocation state before authorizing playback,
So that previously issued tokens are immediately rejected and the viewer cannot use stale credentials to stream video content.

**Acceptance Criteria:**

**Given** a `viewerId` whose account has experienced a security event (password change, account suspension, explicit logout, or security incident)
**When** `PlaybackService.revokeTokensForViewer(viewerId)` is called
**Then** `revokedAt = now` is set on all `PlaybackToken` records for that `viewerId` where `revokedAt IS NULL AND expiresAt > NOW()`
**And** returns the count of revoked tokens

**Given** a `tokenId` (the `jti` claim from a previously issued JWT)
**When** `PlaybackService.validateToken(tokenId)` is called
**Then** if `PlaybackToken.revokedAt IS NOT NULL`, throws `PlaybackDeniedException` (HTTP 403) — token has been revoked
**And** if `PlaybackToken.expiresAt < NOW()`, throws `PlaybackDeniedException` (HTTP 403) — token has expired
**And** if the token is valid (non-revoked, non-expired), returns the `PlaybackToken` metadata for the caller's use

**Given** `authorizePlayback(videoId, viewerId)` is called for a viewer who has a recently revoked token
**When** the playback eligibility check passes
**Then** before issuing a new token, the service checks whether `viewerId` has any `PlaybackToken` with `revokedAt IS NOT NULL AND revokedAt > NOW() - revocation-window` (configurable, default 24 hours)
**And** if a recent revocation exists, `PlaybackDeniedException` (HTTP 403) is thrown with reason `PLAYBACK_DENIED` — the consuming app must explicitly wait out the revocation window to re-enable token issuance for that viewer

**Note:** The consuming app's middleware is responsible for calling `validateToken(tokenId)` when verifying an active playback session — the module provides the validation service; the consuming app decides where in its request handling to invoke it.

**Given** unit tests for token revocation
**When** they run
**Then** `revokeTokensForViewer()` marks only active (non-expired, non-already-revoked) tokens; already-expired tokens are not updated
**And** `validateToken()` rejects a revoked token with `PlaybackDeniedException`; rejects an expired token; accepts a valid active token
**And** `authorizePlayback()` for a viewer with a recent revocation returns `PlaybackDeniedException` rather than issuing a new token

---

## Epic 4: Processing Reliability & Reconciliation

Delivers the dual-track processing pipeline: idempotent webhook event handling with a database-backed outbox for fault tolerance, and a Reconciliation Worker that polls Bunny.net for Videos stuck in non-terminal states.

### Story 4.1: Webhook Processing Pipeline & Dead-Letter Queue

As a system operator,
I want incoming Bunny.net webhooks to be persisted as outbox records and processed by a reliable, idempotent scheduler with dead-letter handling,
So that provider events are never silently lost and permanently failed events are quarantined for review rather than retried forever.

**Acceptance Criteria:**

**Given** no webhook event table exists
**When** Flyway runs `V18__video_webhook_events.sql`
**Then** table `video_webhook_events` is created with: `id` UUID PK, `event_id` VARCHAR NOT NULL UNIQUE (provider's idempotency key), `event_type` VARCHAR NOT NULL, `provider_asset_id` VARCHAR NOT NULL, `raw_payload` TEXT NOT NULL, `status` VARCHAR NOT NULL CHECK (`status` IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')), `attempt_count` INT NOT NULL DEFAULT 0, `error_message` TEXT nullable, `created_at` TIMESTAMP NOT NULL, `processed_at` TIMESTAMP nullable; index on `(status, created_at)` for scheduler queries

**Given** Bunny.net POSTs a webhook to `POST /api/video/webhooks/bunny`
**When** the request arrives
**Then** the endpoint immediately calls `VideoProviderAdapter.verifyWebhook(payload, signature)` — if verification fails, returns HTTP 400 and no outbox record is written
**And** if verification passes, a `VideoWebhookEvent` record is inserted with `status = PENDING` and the provider's event ID stored in `event_id`
**And** the endpoint returns HTTP 200 immediately without processing the event synchronously
**And** the endpoint is annotated `@PreAuthorize("permitAll()")` with a code comment stating this is intentional — authentication is performed via HMAC signature verification in the service layer, not via JWT

**Given** one or more `VideoWebhookEvent` records with `status = PENDING`
**When** `WebhookEventProcessorScheduler` fires on its `@Scheduled(fixedDelay)` interval
**Then** it fetches up to `app.video.reconciliation.batch-size` PENDING events using `SELECT … FOR UPDATE SKIP LOCKED ORDER BY created_at ASC`
**And** for each event, transitions `status` to `PROCESSING`, then dispatches based on `event_type`:
- `video.upload.success` → calls `VideoLifecycleService.transitionOperationalState(videoId, PROCESSING)` (idempotent: already PROCESSING is a no-op)
- `video.encoding.success` → calls `VideoLifecycleService.transitionOperationalState(videoId, READY)`
- `video.encoding.failed` → calls `VideoLifecycleService.transitionOperationalState(videoId, FAILED)`
**And** on success: sets `status = COMPLETED`, `processed_at = now`

**Given** a `VideoWebhookEvent` for an `event_id` that already has a `COMPLETED` record
**When** Bunny.net re-delivers the same event
**Then** the webhook endpoint detects the duplicate `event_id` via UNIQUE constraint and returns HTTP 200 without inserting a second record — no duplicate state transitions occur (FR-26)

**Given** a webhook event dispatch throws an exception
**When** the scheduler retries it
**Then** `attempt_count` is incremented and `error_message` is updated on each attempt
**And** once `attempt_count >= app.video.webhook.max-attempts` (configurable), the event transitions to `FAILED` dead-letter state and is never automatically retried again (NFR-11)

**Given** two application nodes run simultaneously and both schedulers fire at the same time
**When** both attempt to claim the same PENDING events
**Then** `SKIP LOCKED` ensures each event is processed by exactly one node — no duplicate state transitions (NFR-13)

**Given** an integration test extending `BaseVideoIT`
**When** the webhook pipeline is exercised
**Then** a valid Bunny.net webhook advances the correct Video state; a duplicate `event_id` is rejected idempotently; a permanently failing event reaches `FAILED` status after max attempts; two concurrent transactions calling the scheduler receive disjoint event sets via SKIP LOCKED

### Story 4.2: Reconciliation Worker

As a system operator,
I want a background Reconciliation Worker that detects and resolves discrepancies between the module's Video state and the actual state at Bunny.net,
So that Videos stuck in non-terminal states due to missed or dropped webhooks are automatically recovered within 5 minutes.

**Acceptance Criteria:**

**Given** no reconciliation incident table exists
**When** Flyway runs `V19__reconciliation_incidents.sql`
**Then** table `reconciliation_incidents` is created with: `id` UUID PK, `video_id` UUID nullable FK → videos, `incident_type` VARCHAR NOT NULL CHECK (`incident_type` IN ('ORPHANED_ASSET', 'MISSING_ASSET', 'STATE_CORRECTED')), `provider_asset_id` VARCHAR, `description` TEXT, `resolved_at` TIMESTAMP nullable, `created_at` TIMESTAMP NOT NULL

**Given** one or more `Video` records with `operational_state IN ('UPLOADING', 'PROCESSING')`
**When** `ReconciliationWorkerScheduler` fires on its `@Scheduled(fixedDelay)` interval
**Then** it fetches up to `app.video.reconciliation.batch-size` eligible videos using `SELECT … FOR UPDATE SKIP LOCKED`
**And** for each video: calls `VideoProviderAdapter.getAssetStatus(providerAssetId)` OUTSIDE any `@Transactional` boundary

**Given** `getAssetStatus()` returns `READY` for a video with local `operationalState = PROCESSING`
**When** the reconciliation worker processes it
**Then** `VideoLifecycleService.transitionOperationalState(videoId, READY)` is called within a `@Transactional` block
**And** a `ReconciliationIncident` of type `STATE_CORRECTED` is persisted recording the corrected transition

**Given** `getAssetStatus()` returns `AssetStatus.DELETED` or throws an asset-not-found exception
**When** the reconciliation worker processes the affected video
**Then** `VideoLifecycleService.transitionOperationalState(videoId, FAILED)` is called
**And** a `ReconciliationIncident` of type `MISSING_ASSET` is persisted (FR-27)

**Given** a Bunny.net asset exists with no corresponding `Video` record in the module database
**When** detected during admin-triggered reconciliation
**Then** a `ReconciliationIncident` of type `ORPHANED_ASSET` is created with `video_id = null` and `provider_asset_id` populated
**And** no playback URL is issued for the orphaned asset until the incident is resolved (FR-27)

**Given** `getAssetStatus()` throws a transient exception for a specific Video
**When** the reconciliation worker encounters it
**Then** the video is skipped (not transitioned) and will be reconsidered on the next scheduler cycle — a single transient failure does not mark the video as FAILED

**Given** the reconciliation worker is configured with `app.video.reconciliation.fixed-delay-ms` and `app.video.reconciliation.batch-size`
**When** a full reconciliation cycle runs
**Then** all non-terminal videos are polled and discrepancies resolved within 5 minutes total cycle time (NFR-5)

**Given** an integration test extending `BaseVideoIT`
**When** the reconciliation worker is exercised against WireMock-stubbed Bunny.net responses
**Then** a PROCESSING video whose provider status is READY is advanced to READY with a STATE_CORRECTED incident; a PROCESSING video whose provider reports asset missing is marked FAILED with a MISSING_ASSET incident; two concurrent reconciliation runs via SKIP LOCKED process disjoint video sets

---

## Epic 5: Admin Layer, Observability & Production Readiness

Delivers the admin service layer, base admin REST endpoint, `@Observed` instrumentation across all services, and optional provider-dependent media enhancements.

### Story 5.1: Admin Service Layer & REST Endpoint

As a consuming application developer,
I want an admin service layer and base REST endpoint for managing video and session state,
So that I can build operational tooling on top of the module without modifying its internals.

**Acceptance Criteria:**

**Given** the full module from Epics 1–4 is in place
**When** `AdminVideoService` is defined in `platform.video.service`
**Then** it exposes: `setVideoAccessState(videoId, newAccessState)` — wraps `VideoLifecycleService.setAccessState()`; DELETED videos rejected with `TerminalStateViolationException`; `deleteVideo(videoId)` — calls `VideoProviderAdapter.deleteAsset(providerAssetId)` OUTSIDE `@Transactional`, then within `@Transactional` transitions `operationalState = DELETED` and releases quota for any PENDING `UploadSession` via `QuotaProvider.release()`; `getUploadSession(uploadSessionId)` — returns session details for inspection; `listVideoSessions(videoId)` — returns all `UploadSession` records ordered by `createdAt DESC`; `triggerReconciliation(videoId)` — calls `VideoProviderAdapter.getAssetStatus()` and applies FR-27 recovery rules synchronously, returning the resulting state and any `ReconciliationIncident` created

**Given** `AdminVideoResource` is defined in `platform.video.api`
**When** any admin endpoint is called
**Then** `@PreAuthorize` using `SecurityConstants` (admin role) and `@Observed(name = "video.admin.{operation}")` are present on every method
**And** the following endpoints are exposed: `PATCH /api/video/admin/videos/{videoId}/access-state` (HTTP 200 with updated video summary); `DELETE /api/video/admin/videos/{videoId}` (HTTP 204); `GET /api/video/admin/videos/{videoId}/sessions` (HTTP 200); `POST /api/video/admin/videos/{videoId}/reconcile` (HTTP 200 with current state + incident)

**Given** the admin endpoints are designed as extension points
**When** a consuming app developer extends the module
**Then** `AdminVideoResource` and `AdminVideoService` are non-final — consuming apps can extend or decorate without modifying the module

**Given** an integration test extending `BaseVideoIT`
**When** each admin endpoint is exercised
**Then** `PATCH access-state` transitions correctly and rejects DELETED videos; `DELETE` marks the video DELETED and calls `QuotaProvider.release()` for any pending session; `GET sessions` returns all sessions; `POST reconcile` applies FR-27 rules against WireMock-stubbed Bunny.net

### Story 5.2: Observability Instrumentation

As a system operator,
I want named Micrometer metrics, structured logging with request context, and confirmed `@Observed` tracing across all video operations,
So that I can monitor video module health, diagnose latency spikes, and trace requests end-to-end in production.

**Acceptance Criteria:**

**Given** all service and resource methods from Epics 1–5 are in place
**When** `@Observed` annotations are audited
**Then** every public method in `AdminVideoResource`, `VideoService`, `PlaybackService`, `AdminVideoService`, `VideoLifecycleService`, `WebhookEventProcessorScheduler`, and `ReconciliationWorkerScheduler` carries `@Observed(name = "video.{descriptive-operation-name}")`

**Given** a upload, playback, webhook, or reconciliation event occurs
**When** it completes (success or failure)
**Then** the following Micrometer metrics are recorded using named constants defined in a `VideoMetrics` class:
- `video.upload.init.latency` (timer, tags: `provider`, `status`)
- `video.upload.confirm.latency` (timer, tags: `status`)
- `video.playback.authorize.latency` (timer, tags: `status`)
- `video.webhook.processing.latency` (timer, tags: `event_type`, `status`)
- `video.reconciliation.cycle.duration` (timer)
- `video.webhook.queue.depth` (gauge: count of PENDING `video_webhook_events`, sampled each scheduler cycle)
- `video.upload.session.active` (gauge: count of PENDING `upload_sessions`)
- `video.error.count` (counter, tags: `operation`, `error_code`)

**Given** any service or scheduler method executes
**When** an exception occurs or an operation completes
**Then** `@Slf4j` structured logging includes MDC context fields: `videoId`, `ownerId`, `viewerId`, `operation`, `provider` — raw credentials, raw webhook payloads, and JWT signing secrets are never logged (NFR-10)

### Story 5.3: Optional Media Enhancements

As a consuming application developer,
I want access to Bunny.net-generated thumbnails and caption tracks for stored videos,
So that my application can surface richer media experiences without implementing provider-specific API calls directly.

**Acceptance Criteria:**

**Given** the `VideoProviderAdapter` interface from Story 1.3
**When** optional media enhancement methods are added
**Then** the interface gains two new optional default methods: `default String getThumbnailUrl(String providerAssetId) { throw new UnsupportedOperationException("thumbnails not supported"); }` and `default void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) { throw new UnsupportedOperationException("captions not supported"); }`
**And** these additions are backward-compatible — existing implementations that do not override them throw `UnsupportedOperationException`, which `VideoMediaService` catches and wraps as `VideoProviderException` (HTTP 502)

**Given** `BunnyVideoProviderAdapter` is the active provider
**When** `getThumbnailUrl(providerAssetId)` is called
**Then** it returns `https://{cdn-hostname}/{providerAssetId}/thumbnail.jpg` — deterministic from asset ID and CDN hostname, no additional API call required

**Given** `BunnyVideoProviderAdapter.addCaptionTrack(providerAssetId, language, captionFileUrl)` is called
**When** it executes
**Then** it calls the Bunny.net Stream captions API (`POST /library/{libraryId}/videos/{videoId}/captions/{srclang}`) with the caption file URL

**Given** `VideoMediaService` is defined in `platform.video.service`
**When** `getThumbnailUrl(videoId)` is called
**Then** it verifies the `Video` exists and is not DELETED; calls `VideoProviderAdapter.getThumbnailUrl(providerAssetId)` and returns the URL; wraps `UnsupportedOperationException` from non-implementing providers as `VideoProviderException`

**Given** unit tests using WireMock
**When** `BunnyVideoProviderAdapterTest` runs the media enhancement cases
**Then** `getThumbnailUrl` returns the correct deterministic URL; `addCaptionTrack` fires the correct POST to Bunny.net; calling either method on a non-implementing adapter throws `UnsupportedOperationException` which `VideoMediaService` wraps correctly
