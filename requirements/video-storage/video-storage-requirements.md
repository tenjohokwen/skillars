# Generic Video Storage & Streaming Module (Provider-Agnostic Production Specification)

## 1. Purpose

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

## Required Provider Operations

```text id="xpv7z1"
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

# 5.1 Upload Initialization Flow 

## Upload Initiation

Client initiates upload:

```http id="w8k2rk"
POST /videos/uploads/initiate
```

Example payload:

```json id="yulx3r"
{
  "fileName": "session.mp4",
  "fileSizeBytes": 104857600,
  "contentType": "video/mp4"
}
```

---

## Upload Validation Rules

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

## Upload Session Model

Upload sessions are first-class entities.

### UploadSession Fields

```text id="4h0b6m"
id
videoId
ownerId
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

## Client Upload Confirmation Endpoint

After successful client upload completion, the client SHOULD call:

```http id="2t3s1w"
POST /videos/uploads/confirm
```

Payload:

```json id="h0bbr4"
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

# 5.4 Processing State Machine (Revised)

## Processing States

```text id="drg86g"
PENDING_UPLOAD
UPLOADING
UPLOADED
PROCESSING
READY
FAILED
MODERATION_REVIEW
BLOCKED
QUARANTINED
ORPHANED
```

---

## Processing State Rules

* READY indicates technical readiness only.
* READY does NOT imply playback eligibility.
* MODERATION_REVIEW content MUST NOT be playable.
* QUARANTINED content MUST NOT be playable.
* ORPHANED content MUST NOT be playable.

---

## Processing Transitions

```text id="i0rz0u"
PENDING_UPLOAD → UPLOADING
UPLOADING → UPLOADED
UPLOADED → PROCESSING
PROCESSING → READY
PROCESSING → FAILED
PROCESSING → MODERATION_REVIEW
MODERATION_REVIEW → READY
MODERATION_REVIEW → BLOCKED
READY → BLOCKED
READY → QUARANTINED
READY → ORPHANED
```

Illegal transitions MUST be rejected.

---

# 6. Quota Enforcement & Metering

# 6.1 Quota Consistency Model (Revised)

## Consistency Requirements

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

# 6.2 Reservation & Upload Coordination (Revised)

## Reservation Lifecycle

```text id="2ez1qf"
RESERVED
ACTIVE_UPLOAD
PROCESSING
FINALIZED
RELEASED
EXPIRED
ABANDONED
```

---

## Sliding Reservation Expiration

Reservations MUST use sliding expiration semantics.

A reservation MUST NOT expire while:

* upload activity is detected,
* provider upload session remains active,
* transcoding is in progress,
* webhook reconciliation is pending.

Activity MAY include:

* multipart upload progress,
* provider upload status polling,
* upload heartbeats,
* chunk completion events.

---

## Reservation Reconciliation

When provider metadata is received:

```text id="f7nm8h"
actualUploadedBytes != reservedBytes
```

the system MUST reconcile quota usage atomically.

### Reconciliation Rules

| Condition                             | Action                        |
| ------------------------------------- | ----------------------------- |
| actual <= reserved                    | finalize normally             |
| actual > reserved and quota available | expand reservation atomically |
| actual exceeds quota                  | mark QUARANTINED              |

---

## Orphan Upload Handling

If a provider webhook arrives after reservation expiration:

```text id="4g3rmo"
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

## Upload URL & Reservation Synchronization

Upload URL expiration MUST NOT exceed reservation expiration.

If an upload URL expires:

* the reservation MUST also expire,
* OR a replacement upload URL MUST be issued automatically.

Systems MUST NOT allow inactive reservations to indefinitely consume quota.

---

# 6.3 Hierarchical Quota Enforcement (Revised)

## Hierarchical Quota Evaluation

When multiple quota scopes exist:

* user,
* organization,
* team,
* project,

ALL applicable quota checks MUST pass.

Effective upload permission is determined by:

```text id="7cujn4"
ALLOW upload
ONLY IF
all applicable quota scopes have sufficient capacity
```

The most restrictive quota wins.

---

# 6.4 Streaming Quota Accounting (Revised)

## Canonical Bandwidth Source

Estimated bandwidth consumption MUST be derived from:

* actual CDN/provider byte-transfer metrics,
* or provider analytics APIs.

Segment-count estimation MAY only be used:

* as fallback telemetry,
* not for billing-grade enforcement.

Billing and hard quota enforcement MUST use byte-based accounting.

---

# 7. Security Model

## 7.1 Signed Playback URLs

All playback access SHOULD use:

* Time-limited signed URLs
* Session-bound tokens
* Short-lived authorization grants

Expiration SHOULD default between:

* 15 minutes to 2 hours

---

## 7.2 Upload Security

Uploads SHOULD support:

* Signed upload policies
* Multipart verification
* MIME validation
* File size enforcement
* Virus/malware scanning

---

# 7.3 Anti-Abuse Controls (Revised)

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

## 7.4 Authorization Model

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

# 8. Playback Authorization Model (New)

## Dual State Machine Evaluation

The module uses:

* Processing State Machine
* Lifecycle State Machine

These are independent state models.

---

## Playback Eligibility

A video is playable ONLY if:

```text id="q9cw4j"
processingState == READY
AND lifecycleState == ACTIVE
```

Restrictive states in either state machine override playback eligibility.

---

## Lifecycle States

```text id="abz6lk"
ACTIVE
LOCKED
ARCHIVED
DELETED
PURGED
```

---

## Lifecycle Transition Rules

```text id="8xy6ij"
ACTIVE → LOCKED
LOCKED → ACTIVE
LOCKED → ARCHIVED
ARCHIVED → ACTIVE
ACTIVE → DELETED
LOCKED → DELETED
ARCHIVED → DELETED
DELETED → PURGED
```

## 8.1 Supported Formats

Preferred streaming formats:

| Format          | Recommendation |
| --------------- | -------------- |
| HLS             | Recommended    |
| DASH            | Optional       |
| Progressive MP4 | Fallback only  |

---

## 8.2 Codec Recommendations

| Codec      | Recommendation      |
| ---------- | ------------------- |
| H.264/AAC  | Universal default   |
| H.265/HEVC | Optional            |
| AV1        | Future optimization |

---

## 8.3 Playback Optimization

The module SHOULD support:

* Adaptive bitrate streaming
* Thumbnail sprites
* Preview clips
* Chapter markers
* Subtitle tracks
* Playback analytics

---

## 8.4 Precision Seeking

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

## 9.1 Moderation Enforcement (Revised)

Videos MUST NOT become playable until:

* transcoding completes,
* moderation checks complete,
* processingState == READY.

Moderation pipelines MAY operate asynchronously internally, but playback authorization MUST deny access until moderation resolution completes.


Detection categories MAY include:

* Nudity
* Violence
* Hate symbols
* Copyright abuse
* CSAM detection
* Self-harm content

---

## 9.2 Moderation States

```text
SAFE
REVIEW_REQUIRED
BLOCKED
REMOVED
ESCALATED
```

---

## 9.3 Compliance Features

The module SHOULD support:

* GDPR deletion workflows
* COPPA compliance
* Data residency policies
* Legal holds
* Audit logging

## Legal Hold Precedence

Legal holds override:

* retention expiration,
* auto-deletion,
* purge workflows,
* archival deletion policies.

Assets under legal hold MUST NOT be deleted until hold removal.

---

## GDPR Deletion Scope

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
DELETED
PURGED
```

---

## 10.2 Archived Playback Behavior (Revised)

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

## 11.2 Webhook Security (Revised)

Webhook signature verification is REQUIRED.

Unsigned or invalid webhooks MUST be rejected.

Webhook processing MUST support:

* replay protection,
* idempotency,
* timestamp validation,
* signature expiration validation.


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

# 12. Data Model (Recommended)

## 12.1 VideoMetadata

```text id="fzf0f8"
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

---

## Visibility Enum

```text id="klk7hk"
PRIVATE
UNLISTED
PUBLIC
ORGANIZATION
TEAM
```

---

## 12.2 QuotaReservation (Revised)

```text id="81x8k4"
id
videoId
uploadSessionId
ownerId

reservedBytes
finalizedBytes

status

expiresAt
lastActivityAt

createdAt
updatedAt
```

---

## 12.3 PlaybackToken (Revised)

```text id="22zqq9"
id
videoId
viewerId

scope
permissions

ipAddress
expiresAt

createdAt
```

---

### Playback Token Scopes

```text id="v9n8j6"
VIEW_ONLY
DOWNLOAD_ALLOWED
EMBED_ALLOWED
PREVIEW_ONLY
ADMIN_REVIEW
```


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

# 14. Recommended Non-Functional Requirements

| Requirement                       | Recommendation |
| --------------------------------- | -------------- |
| Upload availability               | 99.9%          |
| Playback availability             | 99.95%         |
| Signed URL generation             | <200ms         |
| Webhook processing latency        | <30s           |
| Upload initiation latency         | <500ms         |
| Transcoding SLA (95th percentile) | <10 min        |
| Quota reconciliation latency      | <60s           |
| Orphan reconciliation latency     | <15 min        |

---

# 15. Implementation Recommendations

## Recommended Backend Patterns

* Event-driven architecture
* CQRS for analytics-heavy systems
* Background worker queues
* Idempotent APIs
* Atomic SQL updates
* Provider adapter pattern
* Outbox pattern for event reliability

---

# 16. Example Provider Adapter Strategy

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


