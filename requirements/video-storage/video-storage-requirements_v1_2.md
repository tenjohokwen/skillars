# Generic Video Storage & Streaming Module

## Production Specification v1.2

This document is the authoritative standalone production specification for the Generic Video Storage & Streaming Module. It incorporates all baseline requirements from v1.0 together with security hardening, operational clarifications, and compliance requirements introduced in the v1.2 revision. Where this document conflicts with prior draft specifications, this document takes precedence.

---

# 1. Purpose

The Video Module is a reusable infrastructure component that enables applications to securely upload, process, store, stream, moderate, and manage videos at scale.

The module is designed to:

* Support multiple video providers interchangeably.
* Enforce storage and streaming quotas safely.
* Integrate with billing/subscription systems.
* Support secure playback and compliance requirements.
* Operate asynchronously and cost-efficiently.
* Provide extensible lifecycle management.
* Support advanced playback use cases (analysis, scrubbing, education, coaching, training, media review, etc.).

The architecture intentionally separates:

* **Application-owned metadata and business rules**
* **Provider-owned media processing and delivery**

---

# 2. Core Design Principles

## 2.1 Provider Abstraction Layer

The module MUST use a provider abstraction interface so that underlying video infrastructure can be swapped without affecting application business logic.

Supported provider categories include:

* Managed video streaming providers
* CDN-backed object storage
* Self-hosted transcoding pipelines
* Cloud-native media services

Examples include:

* Bunny.net
* Cloudflare
* Amazon Web Services
* Mux
* Vimeo

Applications MUST NOT directly depend on provider-specific APIs outside the provider adapter layer.

---

## 2.2 Application-Owned Source of Truth

The application database remains the authoritative source for:

* Ownership
* Permissions
* Quotas
* Billing
* Moderation status
* Lifecycle state
* Analytics
* Audit logs
* Retention policies

The external provider is treated as:

* A storage/transcoding/delivery engine
* Not the system of record

### Provider vs Application Canonicality

| Data Type               | Canonical Source |
| ----------------------- | ---------------- |
| Ownership               | Application      |
| Permissions             | Application      |
| Lifecycle state         | Application      |
| Moderation state        | Application      |
| Billing ledger          | Application      |
| Raw transfer bytes      | Provider         |
| Raw transcoding metrics | Provider         |

Provider metrics MAY serve as canonical raw usage evidence for billing reconciliation.

Applications MUST periodically ingest provider metrics into:

* immutable billing ledgers,
* reconciliation tables,
* audit trails.

Provider metrics MUST NOT directly mutate user balances without reconciliation persistence.

---

# 3. High-Level Architecture

## 3.1 Logical Components

### Client Applications

Responsible for:

* Upload initiation
* Chunked uploads
* Playback requests
* Progress reporting

### API Gateway / Backend

Responsible for:

* Authentication
* Authorization
* Quota validation
* Signed upload generation
* Playback authorization
* Metadata persistence
* Event orchestration

### Video Provider Adapter Layer

Abstracts provider-specific operations:

* Upload initialization
* Multipart uploads
* Transcoding configuration
* Playback URL generation
* Webhook normalization
* Asset deletion
* Archival operations

### Metadata Database

Stores:

* Video records
* Quota reservations
* Usage counters
* Moderation states
* Retention states
* Playback policies

### Background Workers

Responsible for:

* Cleanup
* Retry processing
* Expired reservation release
* Archival transitions
* Purging
* Moderation orchestration
* Analytics aggregation

### Event/Webhook Processor

Handles provider callbacks:

* Upload completed
* Transcoding completed
* Processing failed
* Moderation completed
* Asset deleted

---

# 4. Provider Abstraction Contract

## 4.1 Required Provider Capabilities

Every provider adapter implementation MUST support the following capabilities:

| Capability                     | Requirement |
| ------------------------------ | ----------- |
| Direct uploads                 | REQUIRED    |
| Signed playback URLs           | REQUIRED    |
| Webhooks/events                | REQUIRED    |
| Webhook signature verification | REQUIRED    |
| Adaptive streaming             | REQUIRED    |
| Upload expiration policies     | REQUIRED    |
| Upload size enforcement        | REQUIRED    |
| Asset deletion APIs            | REQUIRED    |
| Automated transcoding          | RECOMMENDED |
| Thumbnail extraction           | RECOMMENDED |
| Archival APIs                  | OPTIONAL    |
| DRM support                    | OPTIONAL    |
| Geo restrictions               | OPTIONAL    |

Adapters that do not support REQUIRED capabilities are non-conformant.

---

## 4.2 Provider Interface

### Required Provider Operations

```text
initializeUpload()
confirmUpload()
cancelUpload()

generatePlaybackToken()
generateSignedPlaybackUrl()

archiveVideo()
restoreVideo()
deleteVideo()

getUploadStatus()
getVideoStatus()
getVideoMetadata()

registerWebhook()
verifyWebhookSignature()
```

---

# 5. Upload & Processing Pipeline

## 5.1 Upload Initialization Flow

### Upload Initiation

Client initiates upload:

```http
POST /videos/uploads/initiate
```

Example payload:

```json
{
  "fileName": "session.mp4",
  "fileSizeBytes": 104857600,
  "contentType": "video/mp4"
}
```

---

### Upload Validation Rules

The client-provided file size MUST be treated as:

* advisory metadata only,
* NOT authoritative quota usage.

The backend MUST:

* validate against configured upload caps,
* create a provisional quota reservation,
* enforce upload limits at the provider layer.

The provider upload session MUST include:

* maximum content length,
* upload expiration,
* MIME restrictions,
* multipart upload constraints where supported.

Uploads exceeding provider-side limits MUST be rejected before processing.

---

### Upload Session Model

Upload sessions are first-class entities.

#### UploadSession Fields

```text
id
videoId
ownerId
provider
providerRegion
providerUploadId
reservationId
status
declaredFileSizeBytes
actualFileSizeBytes
uploadUrlExpiresAt
reservationExpiresAt
lastActivityAt
createdAt
```

---

### Client Upload Confirmation Endpoint

After successful client upload completion, the client SHOULD call:

```http
POST /videos/uploads/confirm
```

Payload:

```json
{
  "uploadSessionId": "uuid"
}
```

This endpoint:

* marks upload intent as complete,
* triggers reconciliation polling,
* provides fallback if provider webhooks are delayed or lost.

The system MUST remain functional even if:

* client confirmation is missing,
* provider webhooks are delayed.

Backend performs:

* Authentication
* Quota validation
* Reservation creation
* Upload policy generation
* Provider upload initialization

Returns:

* Temporary upload URL
* Upload token
* Expiration timestamp
* Provider upload session ID

---

## 5.2 Direct-to-Provider Upload

Clients upload directly to the provider/CDN/object storage.

The application backend MUST NOT proxy large video payloads unless explicitly required.

Benefits:

* Reduced infrastructure cost
* Better scalability
* Lower backend bandwidth usage

---

## 5.3 Asynchronous Processing

After upload:

1. Provider processes/transcodes media.
2. Provider emits webhook/event.
3. Event processor validates authenticity.
4. Metadata is finalized.
5. Reservations are converted into permanent usage.

---

## 5.4 Processing State Machine

### Processing States

```text
PENDING_UPLOAD
UPLOADING
UPLOADED
PROCESSING
READY
FAILED
BLOCKED
QUARANTINED
ORPHANED
```

`MODERATION_REVIEW` is not a processing state. Moderation outcome is tracked independently via `moderationStatus` (Section 9.5) and evaluated as a separate gate at playback time (Section 8). When `moderationStatus` transitions to `BLOCKED` or `REMOVED`, the application layer MUST drive `processingState → BLOCKED`.

---

### Processing State Rules

* READY indicates technical readiness only.
* READY does NOT imply playback eligibility.
* Playback MUST be denied unless:

```text
processingState == READY
AND lifecycleState == ACTIVE
AND moderationStatus == SAFE
```

ALL other processingState values are explicitly NON-PLAYABLE, including:

```text
PENDING_UPLOAD
UPLOADING
UPLOADED
PROCESSING
FAILED
BLOCKED
QUARANTINED
ORPHANED
```

---

### Processing Transitions

Base transitions:

```text
PENDING_UPLOAD → UPLOADING
UPLOADING → UPLOADED
UPLOADING → QUARANTINED
UPLOADED → PROCESSING
UPLOADED → QUARANTINED
PROCESSING → READY
PROCESSING → FAILED
READY → BLOCKED
READY → QUARANTINED
READY → ORPHANED
```

`UPLOADING → QUARANTINED` and `UPLOADED → QUARANTINED` cover malware or policy violations detected during or immediately after upload, before transcoding begins.

Recovery transitions:

```text
FAILED → PENDING_UPLOAD
FAILED → PROCESSING

BLOCKED → READY

QUARANTINED → PROCESSING
QUARANTINED → READY
QUARANTINED → BLOCKED

ORPHANED → PROCESSING
ORPHANED → READY
ORPHANED → DELETED
```

Illegal transitions MUST be rejected.

---

### Recovery Semantics

#### FAILED

FAILED indicates technical processing failure only.

Recovery MAY occur via:

* retry transcoding,
* provider migration,
* re-upload,
* manual repair.

---

#### BLOCKED

BLOCKED indicates moderation or policy restriction.

BLOCKED MAY be set:

* directly by the application layer when `moderationStatus` transitions to `BLOCKED` or `REMOVED`,
* by an administrator override,
* by automated policy enforcement.

Recovery requires:

* administrator override,
* successful appeal,
* policy reclassification.

Recovery MUST also reset `moderationStatus` to `SAFE` before the video becomes playable.

---

#### QUARANTINED

QUARANTINED indicates unresolved security, quota, billing, or reconciliation inconsistency.

Playback MUST remain denied until resolution completes.

---

#### ORPHANED

ORPHANED indicates provider-side asset existence without valid application ownership reconciliation.

Recovery requires:

* ownership verification,
* quota reconciliation,
* metadata reconstruction,
* administrative approval.

---

## 5.5 Upload Reconciliation Guarantees

### Mandatory Reconciliation Polling

The system MUST NOT rely exclusively on:

* client confirmation,
* provider webhooks.

Background reconciliation polling is REQUIRED.

---

### Required Polling Cadence

| Upload Age              | Poll Frequency                                          |
| ----------------------- | ------------------------------------------------------- |
| 0–15 min                | every 1 minute                                          |
| 15–60 min               | every 5 minutes                                         |
| 1–24 hr                 | every 30 minutes                                        |
| > 24 hr (ORPHANED)      | every 4 hours                                           |
| > 7 days (ORPHANED)     | stop auto-polling; fire administrative escalation alert |

Assets remaining `ORPHANED` beyond 7 days MUST trigger an administrative escalation alert. No automated state transition occurs beyond 7 days without explicit administrative action.

---

### Orphaned Asset Auto-Remediation Schedule

When the 7-day escalation alert fires, the system MUST compute and persist a `scheduledDeletionAt` timestamp on the `UploadReconciliationJob` record. The alert MUST include this deadline explicitly so the resolution window is visible to the operator.

The following tiered remediation policy MUST apply:

| Threshold         | Automated Action                                                                                                             |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Day 7 (ORPHANED)  | Fire admin escalation alert; set `scheduledDeletionAt` on reconciliation job                                                 |
| Day 14 (ORPHANED) | Auto-archive: snapshot provider-reported `actualFileSizeBytes`; detach from quota; set `lifecycleState = ARCHIVED`; schedule provider-side deletion |
| Day 30 (ORPHANED) | Auto-delete: remove provider-side asset; retain anonymized audit record; transition to `PURGED`                              |

Legal hold checks (Section 9.6) MUST be evaluated before the Day-14 and Day-30 automated transitions. Assets under legal hold MUST NOT be auto-deleted regardless of orphan age.

Each scheduled transition MUST be recorded as an audit event.

---

### Reconciliation Timeout

Uploads unresolved after:

```text
24 hours
```

MUST transition to:

```text
ORPHANED
```

unless administrative recovery succeeds.

---

### Webhook Reliability Model

Webhook processing MUST use:

* idempotency keys,
* replay protection,
* event deduplication,
* durable event persistence,
* dead-letter queues.

If no webhook arrives within expected provider SLA, the reconciliation worker MUST independently query provider status APIs.

---

### Reconciliation Conflict Precedence

When provider-reported state and local database state conflict, the following precedence rules apply:

| Provider Reports | Local DB State | Resolution |
| ---------------- | -------------- | ---------- |
| Asset EXISTS | No local record | Create `ORPHANED` record; use provider-reported bytes as `actualFileSizeBytes`; require admin reconciliation |
| Asset EXISTS | `PROCESSING` | Continue polling; no state change |
| Asset NOT FOUND | `PROCESSING` | Transition to `FAILED`; release reservation atomically |
| Asset NOT FOUND | `ORPHANED` | Confirm orphan; retain `ORPHANED` state |

For all `ORPHANED` records created via conflict detection, `actualFileSizeBytes` MUST be sourced from provider-reported metrics, which are canonical for raw transfer data (Section 2.2).

---

# 6. Quota Enforcement & Metering

## 6.1 Quota Consistency Model

### Consistency Requirements

Quota accounting MUST use strong consistency guarantees.

The following operations MUST be atomic:

* quota reservation,
* reservation finalization,
* quota release,
* usage reconciliation.

Eventual consistency is acceptable ONLY for:

* analytics,
* dashboards,
* non-billing metrics,
* aggregated reporting.

Quota enforcement MUST NOT rely on eventually consistent systems.

---

## 6.2 Reservation & Upload Coordination

### Reservation Lifecycle

```text
RESERVED
ACTIVE_UPLOAD
PROCESSING
FINALIZED
RELEASED
EXPIRED
ABANDONED
```

---

### Sliding Reservation Expiration

Reservations MUST use sliding expiration semantics.

A reservation MUST NOT expire while:

* upload activity is detected,
* provider upload session remains active,
* transcoding is in progress,
* webhook reconciliation is pending.

#### Default Sliding Window

Default extension window:

```text
15 minutes
```

Maximum total reservation lifetime:

```text
24 hours
```

#### Valid Activity Signals

Reservation lifetime MAY extend only when at least one occurs:

| Activity Signal                | Allowed |
| ------------------------------ | ------- |
| Multipart chunk completion     | YES     |
| Provider upload byte growth    | YES     |
| Upload heartbeat               | YES     |
| Provider processing active     | YES     |
| Webhook reconciliation pending | YES     |

Passive inactivity MUST NOT extend reservations.

---

### Pessimistic Expiration in PROCESSING Status

Reservation expiration checks MUST apply pessimistic semantics when a reservation has transitioned to `PROCESSING` status.

A reservation in `PROCESSING` status MUST NOT be expired unless at least one of the following conditions is true:

* a terminal provider event (`READY` or `FAILED` webhook) has been received, OR
* elapsed time since `processingStartedAt` exceeds `MAX_TRANSCODING_DURATION`.

`MAX_TRANSCODING_DURATION` MUST be configured as the provider transcoding SLO (Section 14) plus a minimum safety buffer of 20 minutes. The recommended default is 30 minutes.

Expiration checks that cannot confirm provider processing status due to a polling failure MUST defer expiration to the next check cycle. Optimistic expiration during a polling gap is NON-COMPLIANT.

---

### Reservation Reconciliation

When provider metadata is received:

```text
actualUploadedBytes != reservedBytes
```

the system MUST reconcile quota usage atomically.

#### Reconciliation Rules

| Condition                             | Action                        |
| ------------------------------------- | ----------------------------- |
| actual <= reserved                    | finalize normally             |
| actual > reserved and quota available | expand reservation atomically |
| actual exceeds quota                  | mark QUARANTINED              |

---

### Orphan Upload Handling

If a provider webhook arrives after reservation expiration:

```text
reservation not found
OR
reservation invalid
```

the asset MUST enter:

* ORPHANED state,
* playback denied,
* retention timer started.

Orphaned assets MAY be recovered by administrative reconciliation.

---

### Upload URL & Reservation Synchronization

Upload URL expiration MUST NOT exceed reservation expiration.

If an upload URL expires:

* the reservation MUST also expire,
* OR a replacement upload URL MUST be issued automatically.

Systems MUST NOT allow inactive reservations to indefinitely consume quota.

---

## 6.3 Hierarchical Quota Enforcement

### Hierarchical Quota Evaluation

When multiple quota scopes exist:

* user,
* organization,
* team,
* project,
* tenant,

ALL applicable quota checks MUST pass.

Effective upload permission is determined by:

```text
ALLOW upload
ONLY IF
all applicable quota scopes have sufficient capacity
```

The most restrictive quota wins.

### Required Isolation Guarantees

Implementations MUST use one of:

* SERIALIZABLE isolation,
* distributed locking,
* pessimistic row locking,
* atomic compare-and-swap semantics.

READ COMMITTED isolation alone is NON-COMPLIANT.

### Lock Ordering

To prevent deadlocks, all quota scope locks MUST be acquired in **deterministic lexicographic ascending order** by `(scopeType, scopeId)`.

All implementations MUST use the same canonical lock ordering. Acquiring locks in any other order is NON-COMPLIANT.

### Reservation Algorithm

Quota reservation MUST follow a strict two-phase protocol that isolates the database transaction from external provider calls.

**Phase 1 — Atomic Reservation (database transaction):**

1. lock all affected quota scopes in canonical lexicographic order,
2. validate remaining capacity,
3. reserve capacity atomically,
4. commit all reservations together,
5. rollback fully on failure.

**Phase 2 — Provider Initialization (outside transaction):**

6. call provider upload initialization API after the reservation transaction commits,
7. if the provider call fails, execute a compensating transaction to release the reservation atomically.

Provider network calls MUST NOT occur inside the quota reservation transaction. Holding database row locks during external network I/O is NON-COMPLIANT.

Partial reservation success is prohibited.

---

### Outbox Pattern for Phase 2 Provider Initialization

To eliminate the split-brain window where Phase 1 commits but Phase 2 outcome is unknown, implementations MUST write a `ProviderInitializationJob` record to a durable outbox table atomically within the Phase 1 database transaction:

```sql
-- single atomic transaction
INSERT INTO quota_reservations (...) VALUES (...);
INSERT INTO outbox_provider_jobs (reservation_id, operation, status)
  VALUES (?, 'INITIALIZE_UPLOAD', 'PENDING');
```

A background worker MUST process the outbox entry and execute the provider call. On success, the worker updates both the outbox entry and the upload session in a single transaction. On provider failure, the worker retries with backoff and ultimately executes the compensating reservation release.

If the outbox worker crashes after a successful provider call but before updating the database, the reconciliation poller (Section 5.5) MUST detect the provider-side asset and create an `ORPHANED` record. This ensures no silent data loss — all such cases recover through the standard admin reconciliation path.

Outbox entries MUST NOT be deleted until the associated upload session reaches a terminal state (`FINALIZED`, `RELEASED`, or `ABANDONED`).

---

## 6.4 Streaming Quota Accounting

### Canonical Bandwidth Source

Estimated bandwidth consumption MUST be derived from:

* actual CDN/provider byte-transfer metrics,
* or provider analytics APIs.

Segment-count estimation MAY only be used:

* as fallback telemetry,
* not for billing-grade enforcement.

Billing and hard quota enforcement MUST use byte-based accounting.

---

# 7. Security Model

## 7.1 Signed Playback Authorization

ALL playback access MUST require:

* time-limited signed playback URLs,
* OR signed session-bound playback tokens,
* OR equivalent cryptographically verifiable authorization.

Unsigned public playback URLs are NON-COMPLIANT.

---

### Mandatory Requirements

Playback authorization MUST support:

| Requirement          | Status      |
| -------------------- | ----------- |
| Expiration timestamp | REQUIRED    |
| Signature validation | REQUIRED    |
| Replay mitigation    | REQUIRED    |
| Revocation support   | REQUIRED    |
| Audience binding     | RECOMMENDED |
| IP/session binding   | RECOMMENDED |

---

### Playback Token TTL

Default playback token TTL:

```text
15 minutes
```

Maximum allowed TTL:

```text
2 hours
```

Applications requiring longer access MUST implement rolling refresh authorization.

---

### CDN Cache Constraints

Signed playback authorization MUST NOT rely solely on CDN edge caching rules.

Protected content MUST remain inaccessible after token expiration even if edge assets remain cached.

### Revocation Enforcement Tiers

Playback revocation MUST be enforced across the following tiers in order:

**Tier 1 — Application layer (primary, always enforceable):**
The application MUST NOT issue new signed URLs or playback tokens for assets that are `LOCKED`, `BLOCKED`, or otherwise non-playable. This prevents any new access regardless of CDN state.

**Tier 2 — Short-lived signed URLs (blast-radius limiter):**
Signed playback URL TTL MUST NOT exceed the issuing playback token TTL (default 15 minutes). This limits the window of access for already-issued URLs.

**Tier 3 — CDN cache invalidation (where supported):**
On any revocation event, the system MUST trigger CDN cache invalidation if the provider supports it (e.g., Cloudflare, CloudFront, Bunny.net).

**Tier 4 — Fallback for providers without CDN invalidation:**
For providers that do not support programmatic CDN cache invalidation (e.g., basic object storage without edge functions):

* Signed playback URL TTL MUST NOT exceed **5 minutes**.
* Revocation is effective only after signed URL expiry.
* Applications using these providers MUST explicitly acknowledge this limitation in their compliance documentation.

---

### Edge-Layer Revocation (where supported)

For providers that support edge compute (e.g., Cloudflare Workers, Lambda@Edge), implementations SHOULD implement edge-layer token revocation to make revocation effective independently of CDN cache TTL:

* Embed a `jti` (JWT token identifier) in every signed playback URL.
* Maintain a revocation set in a fast edge-accessible store (e.g., Cloudflare KV, ElastiCache).
* The edge worker MUST check the revocation set before serving content. This makes revocation effective within milliseconds of the application-layer revocation event.

---

### Provider Adapter Playback TTL Contract

Every `VideoProviderAdapter` implementation MUST declare its maximum safe playback URL TTL:

```text
maxPlaybackUrlTtl(): Duration
```

Providers without programmatic CDN cache invalidation MUST return `PT5M` (5 minutes). The `PlaybackTokenService` MUST query this value and MUST reject token issuance requests that specify a TTL exceeding the adapter's declared maximum.

---

### CDN Invalidation Audit Requirements

Every CDN cache invalidation attempt triggered by a revocation event MUST be recorded in the `PlaybackRevocation` record with:

* `cdnInvalidationAttemptedAt` — timestamp when the invalidation request was issued,
* `cdnInvalidationStatus` — outcome: `SUCCEEDED`, `FAILED`, or `NOT_SUPPORTED`.

This ensures the audit trail captures the full window during which content was theoretically accessible after the application-layer revocation was issued.

---

## 7.2 Upload Content Validation

Client-provided MIME type MUST be treated as advisory metadata only.

The backend or provider pipeline MUST validate uploaded content using:

* magic-byte inspection,
* container parsing,
* codec probing,
* extension validation.

---

### Minimum Validation Requirements

Uploads MUST be rejected if:

| Condition                        | Action |
| -------------------------------- | ------ |
| Invalid container format         | Reject |
| Executable masquerading as video | Reject |
| Corrupted media structure        | Reject |
| Unsupported codec policy         | Reject |
| Size exceeds enforced limits     | Reject |

---

### Malware Scanning

Malware scanning is REQUIRED for:

* enterprise deployments,
* public upload systems,
* multi-tenant deployments.

---

## 7.3 Playback Revocation

PlaybackToken MUST support revocation.

The system MUST revoke active playback grants when:

* account suspension occurs,
* moderation block occurs,
* legal restriction occurs,
* billing restriction occurs,
* asset quarantine occurs,
* security incident occurs.

---

## 7.4 Anti-Abuse Controls

The module MUST support:

* upload rate limiting,
* concurrent upload limits,
* abandoned upload cleanup,
* failed upload thresholds,
* quarantined asset thresholds.

Applications SHOULD define:

* max concurrent upload sessions,
* max reserved-but-inactive uploads,
* max daily failed uploads.

---

## 7.5 Authorization Model

The module MUST integrate with application RBAC/ABAC systems.

Recommended permissions:

```text
VIDEO_VIEW
VIDEO_UPLOAD
VIDEO_DELETE
VIDEO_MODERATE
VIDEO_ARCHIVE
VIDEO_ADMIN
```

Ownership checks MUST occur at the application layer.

---

# 8. Playback Authorization Model

## Dual State Machine Evaluation

The module uses:

* Processing State Machine
* Lifecycle State Machine

These are independent state models.

---

## Playback Eligibility

A video is playable ONLY if:

```text
processingState == READY
AND lifecycleState == ACTIVE
AND moderationStatus == SAFE
```

Restrictive states in any of the three dimensions override playback eligibility.

When `moderationStatus` transitions to `BLOCKED` or `REMOVED`, the application layer MUST also drive `processingState → BLOCKED` to enforce the restriction at the storage module level.

---

## Lifecycle States

```text
ACTIVE
LOCKED
ARCHIVED
RESTORING
RESTORE_FAILED
DELETED
PURGED
```

---

## Lifecycle Transition Rules

```text
ACTIVE → LOCKED
LOCKED → ACTIVE
LOCKED → ARCHIVED
ARCHIVED → ACTIVE
ARCHIVED → RESTORING
RESTORING → ACTIVE
RESTORING → ARCHIVED
RESTORING → RESTORE_FAILED
RESTORE_FAILED → RESTORING
RESTORE_FAILED → ARCHIVED
ACTIVE → DELETED
LOCKED → DELETED
ARCHIVED → DELETED
DELETED → PURGED
```

---

## 8.1 LOCKED State Definition

LOCKED indicates temporary administrative restriction while preserving recoverability.

### LOCKED Causes MAY Include

* billing delinquency,
* legal review,
* trust & safety review,
* account suspension,
* enterprise retention freeze,
* internal investigation.

---

### LOCKED Behavior

| Capability             | Allowed              |
| ---------------------- | -------------------- |
| Playback               | NO                   |
| Metadata edits         | Restricted           |
| Administrative restore | YES                  |
| Archival               | YES                  |
| Deletion               | Restricted by policy |

---

## 8.2 Archival Restore State

RESTORING indicates asynchronous recovery from cold storage.

Playback remains denied until:

```text
lifecycleState == ACTIVE
```

---

## 8.2.1 RESTORE_FAILED State

RESTORE_FAILED indicates that an asynchronous restore attempt failed (e.g., provider error, network timeout, or asset corruption detected during restoration).

Playback remains denied while in RESTORE_FAILED.

### RESTORE_FAILED Behavior

| Capability             | Allowed              |
| ---------------------- | -------------------- |
| Playback               | NO                   |
| Metadata edits         | Restricted           |
| Retry restore          | YES                  |
| Administrative cancel  | YES                  |
| Deletion               | Restricted by policy |

Recovery transitions:

```text
RESTORE_FAILED → RESTORING   // operator retries restore
RESTORE_FAILED → ARCHIVED    // operator cancels restore attempt
```

---

## 8.3 PURGED Semantics

PURGED is a terminal state.

No transitions may originate from PURGED.

Applications MAY retain:

* anonymized audit records,
* non-identifiable billing records,
* legal compliance evidence.

Personally identifiable references MUST be removed or irreversibly anonymized.

---

## 8.4 Supported Formats

Preferred streaming formats:

| Format          | Recommendation |
| --------------- | -------------- |
| HLS             | Recommended    |
| DASH            | Optional       |
| Progressive MP4 | Fallback only  |

Adaptive streaming compliance REQUIRES support for at least one standardized adaptive streaming protocol (HLS or DASH). Proprietary-only adaptive delivery is NON-COMPLIANT.

---

## 8.5 Codec Recommendations

| Codec      | Recommendation      |
| ---------- | ------------------- |
| H.264/AAC  | Universal default   |
| H.265/HEVC | Optional            |
| AV1        | Future optimization |

---

## 8.6 Playback Optimization

The module SHOULD support:

* Adaptive bitrate streaming
* Thumbnail sprites
* Preview clips
* Chapter markers
* Subtitle tracks
* Playback analytics

---

## 8.7 Precision Seeking

Applications requiring:

* Coaching
* Sports analysis
* Education review
* Medical review
* Media annotation

SHOULD configure:

* Short segment durations
* Keyframe optimization
* Low-latency manifests

Example recommendation:

* HLS with 2-second segments

---

# 9. Moderation & Compliance

## 9.1 Moderation Enforcement

Videos MUST NOT become playable until:

* transcoding completes,
* moderation checks complete,
* processingState == READY,
* moderationStatus == SAFE.

Moderation pipelines MAY operate asynchronously internally, but playback authorization MUST deny access until moderation resolution completes.

### Moderation State Separation

Moderation is tracked as an orthogonal concern via `moderationStatus` (Section 9.5), separate from `processingState`. The `processingState` machine models technical readiness; `moderationStatus` models policy and compliance verdict. These are independent dimensions evaluated together at the playback gate (Section 8).

**Mapping from `moderationStatus` to `processingState`:**

| moderationStatus transition | Required processingState action |
| --------------------------- | ------------------------------- |
| → BLOCKED                   | Application MUST drive processingState → BLOCKED |
| → REMOVED                   | Application MUST drive processingState → BLOCKED |
| → SAFE                      | processingState unchanged; playback gate re-evaluates |
| → REVIEW_REQUIRED           | No processingState change; playback denied by gate |
| → ESCALATED                 | No processingState change; operational alert fires |

Detection categories MAY include:

* Nudity
* Violence
* Hate symbols
* Copyright abuse
* CSAM detection
* Self-harm content

---

## 9.2 Moderation SLA

Moderation systems MUST define:

| Requirement                  | Value        |
| ---------------------------- | ------------ |
| Initial moderation start     | < 5 minutes  |
| Moderation completion target | < 30 minutes |
| Alert threshold              | 15 minutes   |
| Escalation threshold         | 60 minutes   |

---

## 9.3 Moderation Timeout Handling

If moderation cannot complete within SLA, the asset MUST transition to:

```text
ESCALATED
```

AND:

* playback MUST remain denied,
* operational alerts MUST fire,
* retry orchestration MUST begin.

---

## 9.4 Moderation Failure Isolation

Moderation infrastructure outages MUST NOT stall unrelated upload processing pipelines indefinitely.

Systems SHOULD support:

* queue isolation,
* dead-letter moderation queues,
* moderation retry workers,
* circuit breakers.

---

## 9.5 Moderation States

```text
SAFE
REVIEW_REQUIRED
BLOCKED
REMOVED
ESCALATED
```

---

## 9.6 Compliance Features

The module SHOULD support:

* GDPR deletion workflows
* COPPA compliance
* Data residency policies
* Legal holds
* Audit logging

### Legal Hold Precedence

Legal holds override:

* retention expiration,
* auto-deletion,
* purge workflows,
* archival deletion policies.

Assets under legal hold MUST NOT be deleted until hold removal.

Legal hold supersedes user-requested deletion when legally required.

However:

* processing access MUST be restricted,
* playback MUST be denied,
* data visibility MUST be minimized,
* retention MUST be scoped narrowly,
* auditability MUST be preserved.

### GDPR Erasure Under Legal Hold

When legal hold applies:

| Data Type               | Action             |
| ----------------------- | ------------------ |
| Public playback         | Disable            |
| Search indexing         | Remove             |
| User visibility         | Remove             |
| Metadata visibility     | Minimize           |
| Financial/legal records | Retain if required |
| Evidence preservation   | Retain if required |

Applications MUST maintain jurisdiction-specific legal policies.

This module MUST support configurable precedence policies by jurisdiction.

### GDPR Deletion Scope

GDPR deletion workflows MUST include:

* metadata,
* thumbnails,
* transcoded variants,
* provider-side assets,
* playback tokens,
* viewer associations,
* cached CDN artifacts where supported.

Applications MAY retain:

* irreversibly anonymized audit records,
* legally required financial records.

Personally identifiable references MUST be removed or anonymized.

---

# 10. Lifecycle Management

## 10.1 Lifecycle States

```text
ACTIVE
LOCKED
ARCHIVED
RESTORING
RESTORE_FAILED
DELETED
PURGED
```

See Section 8 for full transition rules and state semantics.

---

## 10.2 Archived Playback Behavior

If playback is requested for an ARCHIVED asset:

Applications MAY:

* deny playback immediately,
* trigger asynchronous restore,
* return restoration ETA,
* notify clients of restoration progress.

The chosen strategy MUST be consistent per application.

---

## 10.3 Archival Strategy

Cold storage MAY use:

* Provider archival tiers
* Object storage archival classes
* Glacier-style systems

Archived content SHOULD:

* Retain metadata
* Preserve ownership
* Be restorable asynchronously

---

## 10.4 Purging

Permanent deletion MUST:

* Remove provider assets
* Remove playback tokens
* Purge metadata
* Clear analytics references
* Revoke CDN access

Deletion workflows SHOULD be idempotent.

### Purge of Assets in RESTORING State

A Purge operation issued against an asset in `RESTORING` lifecycleState MUST be rejected (HTTP 409 Conflict or equivalent).

To purge a restoring asset, the operator MUST first cancel the restore:

```text
RESTORING → ARCHIVED
```

Then issue the Purge from `ARCHIVED` state. This prevents a race condition between an in-flight provider restore completing and the purge workflow removing provider-side assets mid-restore.

---

# 11. Reliability & Scalability

## 11.1 Required Characteristics

The module SHOULD support:

* Horizontal scaling
* Event retries
* Idempotent webhooks
* Queue-based processing
* Multi-region deployment

---

## 11.2 Webhook Security

Webhook signature verification is REQUIRED.

Unsigned or invalid webhooks MUST be rejected.

Webhook processing MUST support:

* replay protection,
* idempotency,
* timestamp validation,
* signature expiration validation,
* event deduplication,
* durable event persistence,
* dead-letter queues.

### Webhook Secret Rotation

Provider adapters MUST support a rotation window during which both the current and previous webhook secrets are accepted simultaneously.

Secret rotation MUST NOT cause assets to enter `ORPHANED` state.

Implementations MUST:

* accept signatures verified by either the current or the previous active secret,
* log rotation events to the audit trail,
* support configurable rotation overlap windows.

#### Rotation Overlap Derivation

The minimum rotation overlap window MUST be computed as the maximum of:

* the provider's maximum webhook retry window,
* the dead-letter queue maximum message retention time,
* 1 hour (hard floor).

Configuring a rotation overlap shorter than this derived minimum is NON-COMPLIANT.

#### Rotation Observability Requirements

During the overlap window, every webhook validated against the previous (outgoing) secret MUST be logged as a distinct audit event. This provides visibility into straggler webhooks and operational evidence for when it is safe to close the overlap window.

Webhooks that fail signature verification against both the current and previous secrets during a rotation window MUST NOT be discarded. They MUST be routed to a rotation-rejection dead-letter queue with the full payload retained for manual review and potential replay.

The timestamp validation skew window (`max_timestamp_skew`) MUST NOT exceed the rotation overlap duration. A skew window larger than the overlap window would permit replay of old-secret-signed webhooks after rotation completes.

---

## 11.3 Observability

The module SHOULD expose:

* Upload metrics
* Playback metrics
* Storage metrics
* Error rates
* Transcoding latency
* Quota consumption

Integrations MAY include:

* Grafana Labs (LGTM stack)

---

# 12. Data Model

## 12.1 VideoMetadata

```text
id
ownerId
provider
providerAssetId

processingState
lifecycleState

moderationStatus
visibility

title
description

durationMs
storageBytes
streamingBytes

mimeType
contentType

legalHold

createdAt
updatedAt
archivedAt
deletedAt
```

### mimeType vs contentType

**contentType** — represents the original HTTP upload `Content-Type` header (e.g. `video/mp4`). May be client-declared.

**mimeType** — represents the validated media MIME type after server/provider inspection (e.g. `video/mp4`). This is the canonical media classification field.

---

## 12.2 Visibility Enum

```text
PRIVATE
UNLISTED
PUBLIC
ORGANIZATION
TEAM
```

---

## 12.3 QuotaReservation

```text
id
videoId
uploadSessionId
ownerId

reservedBytes
finalizedBytes

status

expiresAt
lastActivityAt
processingStartedAt

createdAt
updatedAt
```

`processingStartedAt` MUST be set when the reservation transitions to `PROCESSING` status. It is used by the pessimistic expiration guard (Section 6.2) to compute whether `MAX_TRANSCODING_DURATION` has elapsed before allowing expiry.

### videoId Nullability

`videoId` MAY be nullable before VideoMetadata creation finalization.

| Stage                | videoId  |
| -------------------- | -------- |
| Reservation created  | nullable |
| Upload confirmed     | assigned |
| Processing finalized | required |

---

## 12.4 PlaybackToken

```text
id
videoId
viewerId

scope
permissions

issuedAt
expiresAt

revokedAt
revocationReason

ipAddress
sessionId

createdAt
```

### Playback Token Scopes

```text
VIEW_ONLY
DOWNLOAD_ALLOWED
EMBED_ALLOWED
PREVIEW_ONLY
ADMIN_REVIEW
```

---

## 12.5 PlaybackRevocation

```text
id
playbackTokenId
reason
revokedBy
revokedAt
cdnInvalidationAttemptedAt
cdnInvalidationStatus
createdAt
```

`cdnInvalidationStatus` MUST be one of: `SUCCEEDED`, `FAILED`, `NOT_SUPPORTED`. See Section 7.1 CDN Invalidation Audit Requirements.

---

## 12.6 ModerationJob

```text
id
videoId

provider
status

attempts
lastAttemptAt

escalatedAt
completedAt

createdAt
updatedAt
```

---

## 12.7 UploadReconciliationJob

```text
id
uploadSessionId

provider
lastCheckedAt
nextCheckAt

attempts
status

scheduledDeletionAt

createdAt
updatedAt
```

`scheduledDeletionAt` is set when an `ORPHANED` asset triggers the Day-7 admin escalation alert. It drives the Day-14 auto-archive and Day-30 auto-delete transitions defined in Section 5.5. MUST be null for non-orphaned records.

---

# 13. Extensibility

The module SHOULD support future extensions such as:

* DRM
* Live streaming
* Collaborative annotations
* AI-generated summaries
* Auto-captioning
* Video search
* Face/object detection
* Timeline comments
* Real-time clipping
* Monetized streaming

---

# 14. Non-Functional Requirements

## Application-Controlled SLOs

| Requirement                  | Recommendation |
| ---------------------------- | -------------- |
| Upload availability          | 99.9%          |
| Playback availability        | 99.95%         |
| Signed URL generation        | <200ms         |
| Webhook processing latency   | <30s           |
| Upload initiation latency    | <500ms         |
| Quota reconciliation latency | <60s           |
| Orphan reconciliation latency | <15 min       |

## Provider-Dependent SLOs

| Requirement                       | Recommendation |
| --------------------------------- | -------------- |
| Transcoding SLA (95th percentile) | <10 min        |

### Provider Breach Handling

Applications MUST define:

* alerting thresholds,
* provider failover policy,
* degraded mode behavior,
* retry escalation,
* customer-visible status handling.

---

# 15. Implementation Patterns

### Mandatory Patterns

| Pattern                         | Requirement |
| ------------------------------- | ----------- |
| Idempotent webhook handling     | REQUIRED    |
| Transactional quota reservation | REQUIRED    |
| Durable reconciliation queues   | REQUIRED    |
| Dead-letter queues              | REQUIRED    |
| Replay-safe event processing    | REQUIRED    |
| Structured audit logs           | REQUIRED    |
| Token revocation support        | REQUIRED    |
| Provider polling fallback       | REQUIRED    |

### Recommended Backend Patterns

* Event-driven architecture
* CQRS for analytics-heavy systems
* Background worker queues
* Idempotent APIs
* Atomic SQL updates
* Provider adapter pattern
* Outbox pattern for event reliability

---

# 16. Recommended Production Architecture

```text
Client
  ↓
API Gateway
  ↓
Video Service
  ├── Quota Service
  ├── Authorization Service
  ├── Playback Token Service
  ├── Moderation Orchestrator
  ├── Reconciliation Worker
  ├── Provider Adapter Layer
  └── Audit/Event Service
        ↓
Transactional Database
        ↓
Outbox/Event Bus
        ↓
Async Workers
```

### Provider Adapter Strategy

```text
Application Layer
    ↓
VideoModuleService
    ↓
VideoProviderAdapter Interface
    ├── BunnyAdapter
    ├── CloudflareStreamAdapter
    ├── AwsMediaAdapter
    └── LocalStorageAdapter
```

---

# 17. Final Compliance Rules

An implementation is considered PRODUCTION-COMPLIANT only if it:

* enforces signed playback authorization,
* prevents hierarchical quota races,
* supports orphan reconciliation,
* supports token revocation,
* implements moderation escalation handling,
* guarantees atomic quota reservation semantics,
* implements provider reconciliation polling,
* validates uploaded media server-side,
* supports replay-safe webhook handling,
* defines legal hold precedence behavior,
* prevents playback in all non-READY states.

Non-compliant implementations MUST NOT be certified for production multi-tenant deployment.
