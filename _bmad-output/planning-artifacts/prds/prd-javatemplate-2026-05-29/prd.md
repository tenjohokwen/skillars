---
title: Video Storage & Streaming Module
status: final
created: 2026-05-29
updated: 2026-05-29
project: javatemplate
---

# PRD: Video Storage & Streaming Module

## 0. Document Purpose

This PRD is for developers integrating the Video Module into javatemplate-based applications and for the implementation team building it. It defines what the module does, the integration contract it exposes, and the constraints it operates within. Technology choices, provider comparisons, HLS configuration recommendations, and implementation notes live in `addendum.md`. The Glossary in §3 defines all domain nouns used in this document; downstream artifacts (architecture, epics, stories) must use these terms verbatim.

---

## 1. Vision

The Video Module is a generic, provider-agnostic platform module within the javatemplate framework. It handles secure video upload, processing lifecycle, playback authorization, and background reconciliation for any application that requires video capabilities — social platforms, coaching tools, media-heavy SaaS, and more.

Its defining constraint is that it contains no business rules that vary by application. Quota limits, team semantics, visibility enforcement, and moderation logic differ between apps. Rather than baking in assumptions, the module exposes clean integration points — most importantly the `QuotaProvider` interface — through which consuming applications supply their own logic without modifying the module.

The practical result is that a developer cloning javatemplate gets production-ready video infrastructure on day one, backed by a swappable provider adapter that can be composed with any application's business layer without source edits to the module itself.

---

## 2. Target User

### 2.1 Primary Persona: App Developer

A developer building a new application on javatemplate. They understand Spring Boot and the DDD module structure described in `project-context.md`. They need video upload, lifecycle management, and playback — but not the overhead of evaluating providers, wiring webhook reliability, or re-implementing signed URL mechanics from scratch.

### 2.2 Jobs To Be Done

- Get video upload and playback working in a new app without researching provider SDKs
- Swap video providers without rewriting application code
- Trust that quota safety, security, and reconciliation reliability are handled correctly by the module
- Wire in app-specific rules (quota limits, access policies) without touching module internals

### 2.3 Secondary Persona: End User (Indirect)

Users of applications built on javatemplate — uploaders, viewers, admins — who interact with video through the consuming app's REST layer. The module governs their experience via lifecycle rules and playback authorization, but does not serve them directly; the consuming app mediates all interaction.

### 2.4 Key User Journeys

**UJ-1. App developer integrates the module into a new javatemplate project.**
Developer adds the module, provides a `QuotaProvider` bean (or accepts the no-op default), configures the active provider via Spring properties, and builds their own REST controller delegating to `VideoService`. Video upload and playback work without further changes to the module.

**UJ-2. End user uploads a video via a consuming app.**
The consuming app's REST controller calls `VideoService.initializeUpload(ownerId, fileName, fileSizeBytes)`. The module invokes `QuotaProvider.check()`, creates an Upload Session, requests signed upload credentials from the active provider, and returns them to the app. The end user uploads directly to the provider. The provider webhook (or a client confirmation signal) advances the Video to PROCESSING and eventually to READY.

**UJ-3. End user requests playback.**
The consuming app calls `PlaybackService.authorizePlayback(videoId, viewerId)`. The module checks `operationalState == READY AND accessState == ACTIVE`, issues a signed Playback Token, and returns the provider playback URL. If either condition is not met, the module returns a denial with the disqualifying state.

---

## 3. Glossary

- **Video** — A media asset tracked by the module. Has an Operational State and an Access State. Identified by an `id` and associated with an Owner via `ownerId`.
- **Operational State** — The technical lifecycle phase of a Video: UPLOADING, PROCESSING, READY, FAILED, or DELETED. Reflects what the provider has done with the media.
- **Access State** — The authorization status of a Video: ACTIVE, BLOCKED, or ARCHIVED. Reflects whether the application permits interaction with the Video.
- **Upload Session** — A time-bounded record representing an active upload attempt. Holds a quota reservation handle. Transitions: PENDING → COMMITTED (on confirmation) or EXPIRED (on timeout).
- **Playback Token** — A signed, short-lived credential authorizing playback of a specific Video for a specific viewer. Issued by the module's Playback Authorization service.
- **QuotaProvider** — An interface the consuming application implements to supply quota enforcement. The module calls it before creating an Upload Session.
- **Provider** — An external video infrastructure service (Bunny.net, Mux, Cloudflare Stream, AWS Media Services) that handles media storage, transcoding, and streaming delivery.
- **Provider Adapter** — The implementation of the provider interface in `infrastructure.video` that translates module operations into provider-specific API calls.
- **Owner** — The application-level entity identified by `ownerId` that is responsible for a Video. Team or group membership semantics are the consuming app's concern.
- **Visibility** — A stored attribute on a Video indicating its intended access scope: PRIVATE, GROUP, or UNLISTED. Enforcement of Visibility semantics is the consuming app's responsibility.
- **Reconciliation Worker** — A background job that polls the active provider to detect and recover from missed webhook events.
- **Admin Layer** — The service layer (and optional base REST endpoint) exposing administrative operations: state overrides, session inspection, and Reconciliation Worker triggers.

---

## 4. Features

### 4.1 Upload Management

Upload Management covers the full lifecycle of getting a video from a client device to a provider: quota check, Upload Session creation, direct-to-provider upload, and confirmation via webhook or client signal. The consuming app calls `VideoService.initializeUpload()` and the module orchestrates the rest. Realizes UJ-2.

**Functional Requirements:**

#### FR-1: Upload initialization

`VideoService.initializeUpload(ownerId, fileName, fileSizeBytes)` MUST be available to consuming applications. Before creating an Upload Session, the module MUST invoke `QuotaProvider.check(ownerId, fileSizeBytes)` and reject the request if quota is denied.

**Consequences:**
- Returns signed upload credentials from the active provider on success.
- Returns a quota-denied error if `QuotaProvider.check()` returns false.
- An Upload Session record is created in PENDING status after successful initialization.

#### FR-2: Quota reservation

After `QuotaProvider.check()` passes, the module MUST call `QuotaProvider.reserve(ownerId, fileSizeBytes)` and store the returned reservation handle on the Upload Session.

**Consequences:**
- The Upload Session holds a reservation handle at creation time.
- The reservation handle is available for `commit()` or `release()` at confirmation or expiry.

#### FR-3: Direct provider upload

Video content MUST be uploaded by clients directly to the provider using the signed URL returned by FR-1. The module MUST NOT proxy video payloads through the application backend.

**Consequences:**
- No video bytes pass through the application backend.

#### FR-4: Upload confirmation

The module MUST advance an Upload Session to COMMITTED and the Video to PROCESSING on either: (a) a valid provider webhook signaling upload complete, or (b) a client-side confirmation call to the module. Both paths are supported; either is sufficient alone.

**Consequences:**
- `QuotaProvider.commit(reservationHandle)` is called on successful confirmation.
- Duplicate confirmation signals (webhook + client) are handled idempotently.

#### FR-5: Upload session expiry

Upload Sessions MUST expire automatically after a configurable TTL if not confirmed. On expiry, the module MUST call `QuotaProvider.release(reservationHandle)` and mark the Upload Session EXPIRED.

**Consequences:**
- No dangling quota reservations from abandoned or failed uploads.
- A background job enforces expiry; expiry does not require a client action.

**Feature-specific NFR:** Upload Session TTL MUST be configurable via Spring properties.

#### FR-6: Upload validation

The module MUST validate file size, MIME type, and container format before creating an Upload Session. Client-provided MIME types are advisory only; server-side validation is authoritative.

Validation bounds MUST be configurable via Spring properties. Default values shipped with the module:

| Attribute | Default | Property |
|---|---|---|
| Maximum file size | 5 GB | `video.upload.max-bytes` |
| Allowed MIME types | video/mp4, video/quicktime, video/webm, video/x-msvideo | `video.upload.allowed-mime-types` |
| Allowed container formats | MP4, MOV, WebM, AVI | `video.upload.allowed-formats` |

**Consequences:**
- Requests exceeding the size limit or with disallowed MIME type or container format are rejected before any provider call is made.
- Consuming apps configure tighter or broader bounds via properties without modifying the module.

#### FR-7: Rate limiting

Upload initialization MUST be rate-limited per caller.

**Consequences:**
- Callers exceeding the rate limit receive a rejection response.

**Note:** [ASSUMPTION: rate limit values are configurable via Spring properties; specific defaults to be confirmed at implementation.]

---

### 4.2 Video Lifecycle

Every Video has two orthogonal state dimensions: Operational State (what the provider has done with the media) and Access State (whether the app permits interaction). These together gate all downstream operations, most critically playback. Realizes UJ-2, UJ-3.

**Functional Requirements:**

#### FR-8: Operational states

The module MUST maintain one of the following Operational States for every Video.

| State | Meaning |
|---|---|
| UPLOADING | Upload Session is active |
| PROCESSING | Provider is transcoding |
| READY | Transcoding complete; playback eligible if Access State permits |
| FAILED | Processing failed; upload retry is permitted |
| DELETED | Terminal; no further operations permitted |

**Consequences:**
- The current Operational State is readable by the consuming app via the service layer.

#### FR-9: Access states

The module MUST maintain one of the following Access States for every Video.

| State | Meaning |
|---|---|
| ACTIVE | Playback permitted when Operational State also allows |
| BLOCKED | Playback denied regardless of Operational State |
| ARCHIVED | Video in low-cost provider storage; playback may require restoration |

BLOCKED may be set for any app-determined reason — for example, owner or admin restriction, account suspension, or security incident response. The module stores the state; the consuming app determines when and why to set it.

#### FR-10: Playback eligibility gate

A Video is playback-eligible ONLY when `operationalState == READY AND accessState == ACTIVE`. All other combinations MUST result in a denied playback response.

**Consequences:**
- Module returns a denial with the disqualifying state(s) when playback is requested for an ineligible Video.

#### FR-11: Terminal state enforcement

DELETED is a terminal state. The module MUST reject any operation that would transition a DELETED Video to another Operational State.

**Consequences:**
- Attempts to modify a DELETED Video return an error.

#### FR-12: FAILED retry

A Video in FAILED Operational State MUST be eligible to restart the upload flow. The module MUST support a retry operation that creates a new Upload Session on the existing Video record. The Video remains in FAILED state until the new Upload Session is confirmed; on confirmation it advances to PROCESSING as normal. No new Video entity is created.

**Consequences:**
- The Video `id` is stable across retry attempts; consuming apps referencing the Video by id do not need updating.
- A FAILED Video with an active retry Upload Session is still FAILED until confirmed.

#### FR-33: Visibility modes

The module MUST track one of the following Visibility values on every Video:

| Mode | Signal |
|---|---|
| PRIVATE | Owner-only access |
| GROUP | Group-access — group membership enforcement is the consuming app's responsibility; the module stores the label |
| UNLISTED | Access-by-link; not publicly listed |

#### FR-34: Visibility enforcement boundary

The module stores and returns Visibility. It does not gate playback on Visibility — only on Operational State and Access State (FR-10). Consuming apps must apply Visibility enforcement in their own service or REST layer.

---

### 4.3 Playback Authorization

The module issues signed, short-lived Playback Tokens for authorized viewers. It does not expose unsigned provider URLs through any service path. The consuming app calls the playback authorization service; the module checks eligibility and returns a signed playback URL. Realizes UJ-3.

**Functional Requirements:**

#### FR-13: Signed playback only

All playback MUST use signed Playback Tokens or provider-secured playback mechanisms. Unsigned public playback URLs MUST NOT be returned by any service path.

#### FR-14: Playback eligibility check before token issuance

The module MUST verify the playback eligibility gate (FR-10) before issuing a Playback Token.

**Consequences:**
- Playback requests for ineligible Videos return a denial with reason; no token is issued.

#### FR-15: Playback token requirements

Issued Playback Tokens MUST:
- Expire automatically
- Be cryptographically signed
- Include an `issuedAt` timestamp
- Include a unique token identifier

Playback Tokens SHOULD include `userId`, `sessionId`, and `expiration` claims.

#### FR-17: Token TTL

Default Playback Token TTL is 15 minutes; maximum is 2 hours. Both values MUST be configurable via Spring properties.

**Consequences:**
- Tokens older than TTL are rejected on server-side validation.
- [ASSUMPTION: TTL validation occurs server-side before the provider URL is returned, not only at the provider level.]

#### FR-18: Token revocation

The module MUST support invalidation of active Playback Tokens for a given viewer on: password change, account suspension, explicit logout, or security incident signal.

**Consequences:**
- After revocation, previously issued tokens for that viewer are denied on server-side check.
- After revocation, the module MUST block new Playback Token issuance for the affected viewer for a configurable revocation window (default 24 hours, property `app.video.playback.revocation-window-hours`). The consuming app must wait out this window — or configure it to zero to disable the block — before the viewer can receive new tokens.
- [ASSUMPTION: the consuming app triggers revocation via a module service call supplying the viewerId; the module does not initiate revocation autonomously.]

Playback Tokens MUST be persisted as records in the application database. Revocation (FR-18) is implemented by setting `revokedAt` on the `PlaybackToken` record; server-side validation checks this field before returning a playback URL.

---

### 4.4 Provider Abstraction

The Provider Adapter interface in `infrastructure.video` is the sole boundary between the module and any external video infrastructure service. All provider-specific logic lives behind this interface. Swapping providers requires only a configuration change and a matching adapter implementation. Realizes UJ-1.

**Functional Requirements:**

#### FR-19: Provider adapter interface

The module MUST define a Provider Adapter interface in `infrastructure.video`. No code in `platform.video` may import or depend on a specific provider SDK directly.

#### FR-20: Required adapter operations

Every Provider Adapter implementation MUST implement the following operations:

| Method | Responsibility |
|---|---|
| `initializeUpload()` | Request a direct upload URL from the provider |
| `getAssetStatus()` | Poll current transcoding / processing status |
| `generatePlaybackUrl()` | Return a signed provider playback URL |
| `deleteAsset()` | Remove media from the provider |
| `verifyWebhook()` | Validate webhook signature and parse the event payload |

**Consequences:**
- A provider implementation that does not implement all required operations cannot be used.

#### FR-21: Optional adapter operations

Provider Adapter implementations MAY implement:

| Method | Responsibility |
|---|---|
| `archiveAsset()` | Move asset to low-cost provider storage tier |
| `restoreAsset()` | Restore an archived asset for playback |

#### FR-22: Provider capability baseline

Every provider implementation MUST support: direct uploads, signed playback URLs, webhook events, adaptive streaming, and asset deletion.

#### FR-23: MVP provider

Bunny.net MUST be the MVP provider implementation. Provider selection MUST be configurable via Spring properties.

**Consequences:**
- Swapping from Bunny.net to another supported provider requires only a property change and the presence of a compatible adapter implementation; no `platform.video` code changes.

---

### 4.5 Processing & Reconciliation

The module uses a dual-track approach to processing events: webhooks for real-time state transitions, and a Reconciliation Worker that polls for fault tolerance. This ensures no Video is permanently stuck in PROCESSING due to a missed or dropped webhook. Realizes UJ-2.

**Functional Requirements:**

#### FR-24: Webhook processing

The module MUST process provider events delivered via webhook. Webhook payloads MUST be validated via the Provider Adapter's `verifyWebhook()` before any state transition occurs.

**Consequences:**
- Unverified or malformed webhooks are rejected without state changes.

#### FR-25: Polling fallback reconciliation

The module MUST run a Reconciliation Worker as a background job that polls provider asset status for Videos in non-terminal states. The module MUST NOT rely solely on webhooks.

**Consequences:**
- A Video stuck in PROCESSING with no webhook received is detected and resolved by the Reconciliation Worker.

#### FR-26: Idempotent event processing

Webhook and polling-based event processing MUST be idempotent. Re-delivery of the same event MUST NOT produce duplicate state transitions or duplicate records.

**Consequences:**
- Processing the same webhook payload twice leaves the Video in the same final state as processing it once.

#### FR-27: Reconciliation rules

The Reconciliation Worker MUST apply the following recovery rules:

| Condition | Action |
|---|---|
| Provider reports asset exists; local state is missing | Create a recovery record; deny playback until reviewed by the Admin Layer |
| Provider reports asset is missing | Mark Video as FAILED |

**Implementation constraint:** The automated Reconciliation Worker iterates over Videos known to the local database. Fully orphaned assets — where a provider asset exists but no corresponding `Video` record was ever created locally — cannot be detected by the automated worker, as the worker has no record to poll against. Detection of such orphaned assets requires an explicit admin-triggered reconciliation that performs an inverse provider-asset-list scan. The automated worker applies the "provider reports asset missing" rule (row 2) only; the "provider reports asset exists; local state is missing" rule (row 1) is applied during admin-triggered reconciliation.

#### FR-28: Reconciliation SLA

The Reconciliation Worker MUST complete a full cycle and resolve detected discrepancies within 5 minutes.

---

### 4.6 Integration Contract

The Integration Contract is the boundary between the module and the consuming application. It includes the `QuotaProvider` interface (consumed by the module during upload) and the Admin Layer (consumed by the app when administrative operations are needed). Realizes UJ-1.

**Functional Requirements:**

#### FR-29: QuotaProvider interface

The module MUST define a `QuotaProvider` interface in `platform.video.contract` with the following operations:

| Method | Description |
|---|---|
| `check(ownerId, requestedBytes)` | Returns approval or denial |
| `reserve(ownerId, bytes)` | Returns a reservation handle |
| `commit(reservationHandle)` | Finalizes quota after successful upload |
| `release(reservationHandle)` | Cancels quota reservation on failure or expiry |

The module MUST fail fast at startup if no `QuotaProvider` bean is registered. A `NoOpQuotaProvider` implementation MUST be available as an explicit opt-in for apps that enforce quota externally — but it must be wired deliberately; the module will not silently default to it.

#### FR-30: QuotaProvider concurrency contract

The `QuotaProvider` contract documentation MUST explicitly state that implementations are responsible for concurrent-safe quota enforcement. The module orchestrates the call sequence but does not add additional locking.

**Consequences:**
- Implementations may use transactional reservations, atomic compare-and-swap, or row-level locking as appropriate to their datastore.

#### FR-31: Admin service layer

The module MUST expose an Admin service layer in `platform.video.service` covering:
- Video Access State overrides: ACTIVE, BLOCKED, DELETED
- Upload Session inspection
- Reconciliation Worker manual trigger

**Consequences:**
- Consuming apps build admin tooling on this service without modifying the module.

#### FR-32: Admin resource endpoint

The module MUST expose a base admin REST endpoint in `platform.video.api`. It MUST be secured with `@PreAuthorize` using `SecurityConstants` and MUST be designed as an extension point for consuming apps.

**Out of Scope:** End-user REST controllers. Consuming apps own their own REST surface for end-user video operations.

**Note:** Team or group video sharing — associating a Video with a team, group, or cohort — is entirely the consuming app's responsibility. The module tracks Visibility (PRIVATE / GROUP / UNLISTED) as a label only; the app maps its group model onto that label and enforces access accordingly.

---

### 4.7 Playback Optimization

These are provider-dependent enhancements that are not required for core functionality and may be deferred based on provider support.

**Functional Requirements:**

#### FR-35: Adaptive streaming

The module MUST support HLS streaming with adaptive bitrate playback via the active provider. This aligns with FR-22, which requires every provider implementation to support adaptive streaming.

#### FR-36: Optional media enhancements

Where the active provider supports them, the module SHOULD support: thumbnail generation and preview scrubbing; subtitle tracks and chapter markers.

---

## 5. Cross-Cutting NFRs

### 5.1 Performance

| Requirement | Target |
|---|---|
| Upload initialization latency | < 500ms p99 |
| Playback token generation latency | < 200ms p99 |
| Upload service availability | 99.9% |
| Playback service availability | 99.95% |
| Reconciliation cycle completion | ≤ 5 minutes |

### 5.2 Security

- All playback URLs MUST be signed or provider-secured. Unsigned public URLs are not permitted through any service path.
- All webhook payloads MUST have signatures validated before any state transition occurs.
- Upload initialization MUST be rate-limited.
- Playback providers SHOULD enforce allowed origins/domains where the provider supports it.
- Raw provider credentials and API keys MUST NOT appear in logs or API responses.

### 5.3 Reliability

- Background jobs MUST use dead-letter queues for failed events.
- All background job processing MUST be retry-safe and idempotent.
- The module MUST support horizontal scaling.

### 5.4 Observability

Module services MUST use `@Observed(name = "...")` annotations per the javatemplate convention. [ASSUMPTION: this covers the standard Micrometer/OTEL instrumentation already established in the project.]

---

## 6. Non-Goals

- Subscription billing or quota limit values — set by consuming apps via `QuotaProvider`
- Moderation policy decisions — the Access State BLOCKED is the hook for apps that act on moderation outcomes; the module does not initiate moderation
- Group membership enforcement — module stores the Visibility label (PRIVATE / GROUP / UNLISTED); the consuming app enforces it
- DRM enforcement
- Enterprise legal compliance
- End-user REST controllers — consuming apps build their own
- Public video visibility — optional extension, not in core

---

## 7. MVP Scope

### 7.1 In Scope

- Upload Management: FR-1 through FR-7
- Video Lifecycle: FR-8 through FR-12
- Playback Authorization: FR-13 through FR-18
- Provider Abstraction with Bunny.net implementation: FR-19 through FR-23
- Processing & Reconciliation: FR-24 through FR-28
- Integration Contract — QuotaProvider interface + Admin service layer + Admin endpoint: FR-29 through FR-32
- Visibility attribute storage: FR-33 through FR-34 (within §4.2 Video Lifecycle)

### 7.2 Out of Scope for MVP

- Additional provider implementations (Mux, Cloudflare Stream, AWS Media Services) — architecture supports them; Bunny.net is first
- Playback optimization features (FR-35 through FR-37) — provider-dependent; deferrable without architectural impact
- Timeline annotations, AI clip generation, auto-tagging, player highlights, subtitle generation, mobile offline downloads — future extensions
- Public visibility mode

---

## 8. Success Metrics

**Primary**

- **SM-1: Module adoptability** — The module integrates into a second app type (different from the first) with zero source edits to the module. Validates FR-29, FR-33, FR-34.
- **SM-2: Provider portability** — Swapping from Bunny.net to a second provider requires only a Spring property change and a new adapter implementation, with no changes to `platform.video`. Validates FR-19, FR-23.

**Secondary**

- **SM-3: QuotaProvider contract clarity** — An App Developer can implement `QuotaProvider` correctly from the interface and its contract documentation, without reading module internals. Validates FR-29, FR-30.
- **SM-4: Upload reliability** — Fewer than 0.1% of confirmed uploads fail to advance from PROCESSING to READY within 10 minutes. Validates FR-25, FR-28.

**Counter-metrics (do not optimize)**

- **SM-C1: No business logic leakage** — No class in `infrastructure.video` imports from `platform.*` or contains business rules (quota logic, MIME type policies, group semantics). Counterbalances SM-1: adoption must not be achieved by baking in app-specific defaults.
- **SM-C2: No proxy upload traffic** — Video bytes must not pass through the application backend on any provider. Counterbalances SM-2: a provider swap must not introduce a proxied upload path.

---

## 9. Assumptions Index

- **§4.1 / FR-7** — Rate limit values are configurable via Spring properties.
- **§4.1 / FR-7** — Specific default rate limit values are to be confirmed at implementation.
- **§4.3 / FR-17** — Playback Token TTL validation occurs server-side before the provider URL is returned, not only at the provider level.
- **§4.3 / FR-18** — The consuming app triggers Playback Token revocation via a module service call supplying the `viewerId`; the module does not initiate revocation autonomously.
- **§4.6 / FR-29** — `NoOpQuotaProvider` must be explicitly wired by the consuming app; the module fails fast if no implementation is registered.
- **§4.2 / FR-33** — GROUP visibility membership enforcement is the consuming app's responsibility; the module stores the label only.
- **§5.4** — Module services use `@Observed` annotations per the Micrometer/OTEL conventions already established in the project.
- **§4.6 / FR-30** — QuotaProvider implementations are responsible for concurrent-safe quota enforcement; the module does not add supplementary locking.
