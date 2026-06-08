
---

# Video Storage & Streaming Module

## Simplified Production Specification (Coaching Platform Edition)

# 1. Purpose

The Video Module provides secure upload, processing, storage, and streaming of coaching videos.

Primary use cases:

* analysis (e.g match analysis)
* review
* feedback
* Team video sharing
* Mobile playback
* Timeline scrubbing and annotations

The module is designed for:

* private/team-oriented video access,
* scalable direct uploads,
* secure playback,
* provider portability,
* asynchronous media processing.

The module is NOT responsible for:

* subscription billing systems,
* enterprise legal compliance engines,
* DRM enforcement,
* advanced moderation policy decisions.

---

# 2. Core Design Principles

## 2.1 Provider Abstraction

Applications interact only with the Video Module.

The underlying provider may be swapped without changing application logic.

Supported provider examples:

    * Bunny.net
    * Cloudflare Stream
    * Mux
    * AWS Media Services

* For the MVP bunny.net will be used
---

## 2.2 Application-Owned Metadata

The application database is the source of truth for:

* ownership
* permissions
* visibility
* team associations
* playback authorization
* quotas
* annotations

The provider is responsible only for:

* media storage
* transcoding
* streaming delivery

---

# 3. High-Level Architecture

```text
Client App
    ↓
API Backend
    ↓
Video Service
    ├── Upload Service
    ├── Playback Service
    ├── Provider Adapter
    ├── Reconciliation Worker
    └── Background Jobs
            ↓
      Video Provider
```

---

# 4. Provider Requirements

Every provider adapter MUST support:

| Capability           | Requirement |
| -------------------- | ----------- |
| Direct uploads       | REQUIRED    |
| Signed playback URLs | REQUIRED    |
| Webhooks/events      | REQUIRED    |
| Adaptive streaming   | REQUIRED    |
| Asset deletion       | REQUIRED    |

Optional capabilities:

* thumbnails
* captions
* archival storage
* DRM

---

# 5. Provider Adapter Interface

```text
initializeUpload()
getAssetStatus()
generatePlaybackUrl()
deleteAsset()
verifyWebhook()
```

Optional:

```text
archiveAsset()
restoreAsset()
```

---

# 6. Upload Flow

## 6.1 Upload Initialization

Client requests upload:

```http
POST /videos/uploads
```

Example:

```json
{
  "fileName": "training-session.mp4",
  "fileSizeBytes": 104857600
}
```

Backend:

1. validates upload limits,
2. reserves quota,
3. creates upload session,
4. requests provider upload URL,
5. returns signed upload data.

---

## 6.2 Direct Uploads

Clients upload directly to the provider.

The backend MUST NOT proxy large video payloads.

Benefits:

* lower infrastructure cost,
* improved scalability,
* faster uploads.

---

## 6.3 Upload Confirmation

Provider webhook OR client confirmation finalizes upload.

System MUST support both.

---

# 7. Video Lifecycle

## 7.1 Operational States

```text
UPLOADING
PROCESSING
READY
FAILED
DELETED
```

### Rules

* Only READY videos are playable.
* FAILED videos may be retried.
* DELETED is terminal.

---

## 7.2 Access States

```text
ACTIVE
BLOCKED
ARCHIVED
```

### ACTIVE

Normal playback allowed.

### BLOCKED

Playback denied.

Reasons may include:

* coach/admin restriction,
* moderation,
* account suspension.

### ARCHIVED

Video stored in low-cost storage.

Playback may require restoration delay.

---

# 8. Playback Authorization

All playback MUST use:

* signed playback URLs,
* short-lived playback tokens,
* or provider-secured playback mechanisms.

Unsigned public playback URLs are NOT allowed.

---

## Playback Eligibility

Playback is allowed ONLY if:

```text
videoState == READY
AND accessState == ACTIVE
```

---

## Playback Token Rules

Playback tokens MUST:
- expire automatically,
- be cryptographically signed,
- contain issuedAt timestamps,
- contain unique token identifiers.

The system MAY invalidate previously issued playback tokens when:
- password changes,
- account suspension,
- explicit logout,
- security incidents occur.

Playback tokens SHOULD include:
- userId
- sessionId
- expiration

Recommended defaults:

| Setting     | Value      |
| ----------- | ---------- |
| Token TTL   | 15 minutes |
| Maximum TTL | 2 hours    |

* validate on server-side before issuing playback URLs
---

# 9. Quota Enforcement

The system MUST support:

* per-user quotas,
* upload size limits.

The system MUST prevent users or teams from exceeding allocated storage quotas.

Quota validation MUST occur before upload initialization.

Implementations MUST ensure that concurrent uploads cannot bypass quota enforcement through race conditions.

Recommended implementation approaches include:

* atomic database updates,
* row-level locking,
* compare-and-swap operations,
* transactional quota reservations.

Quota reservations MUST expire automatically if uploads do not complete.
Playback providers SHOULD enforce allowed domains/origins where supported.

---

## Reservation Model

Quota reservations are temporary.

Reservation states:

```text
PENDING
COMMITTED
EXPIRED
```

Unused reservations MUST expire automatically.

---

# 10. Processing & Reconciliation

The system MUST support:

* webhook-based processing,
* polling fallback reconciliation,
* retry-safe event handling.

The system MUST NOT rely solely on webhooks.

---

## Reconciliation Rules

If provider reports asset exists but local state is missing:

* create recovery record,
* deny playback until reviewed.

If provider reports asset missing:

* mark upload FAILED.

---

# 11. Security Requirements

## Required

| Requirement                  | Status   |
| ---------------------------- | -------- |
| Signed playback URLs         | REQUIRED |
| Upload validation            | REQUIRED |
| Webhook signature validation | REQUIRED |
| Rate limiting                | REQUIRED |

---

## Upload Validation

Uploads MUST validate:

* file size,
* MIME type,
* container format.

Client-provided MIME types are advisory only.

---

# 12. Moderation

Moderation is OPTIONAL and application-configurable.

Supported modes:

```text
DISABLED
PRE_PUBLISH
POST_PUBLISH
```

Default recommendation:

```text
POST_PUBLISH
```

for coaching/team environments.

---

# 13. Playback Optimization

The module SHOULD support:

* HLS streaming
* adaptive bitrate playback
* thumbnails
* preview scrubbing
* chapter markers
* subtitle tracks

Recommended coaching configuration:

* HLS streaming
* 2-second segments
* frequent keyframes

This improves:

* seeking precision,
* tactical review,
* frame-by-frame analysis.

---

# 14. Data Model

## Video

```text
id
ownerId
teamId

provider
providerAssetId

videoState
accessState

title
description

durationMs
storageBytes

visibility

createdAt
updatedAt
```

---

## UploadSession

```text
id
videoId

providerUploadId

status

reservedBytes

expiresAt
createdAt
```

---

## PlaybackToken

```text
id
videoId

viewerId

expiresAt
revokedAt

createdAt
```

---

# 15. Visibility

Supported visibility modes:

```text
PRIVATE
TEAM
UNLISTED
```

Public video support is optional.

---

# 16. Reliability Requirements

The system SHOULD support:

* retry-safe webhooks,
* idempotent processing,
* background workers,
* dead-letter queues,
* horizontal scaling.

---

# 17. Recommended Production Stack

## Recommended Providers

### Simplest

* Mux

### Cost Optimized

* Bunny.net + Object Storage

### Flexible

* Cloudflare Stream

---

## Recommended Backend Stack

* PostgreSQL
* Redis
* Queue workers
* Object-based provider uploads
* Event-driven processing

---

# 18. Non-Functional Targets

| Requirement               | Recommendation |
| ------------------------- | -------------- |
| Upload availability       | 99.9%          |
| Playback availability     | 99.95%         |
| Upload init latency       | <500ms         |
| Playback token generation | <200ms         |
| Upload reconciliation     | <5 minutes     |

---

# 19. Future Extensions

The module SHOULD support future additions:

* timeline annotations
* comments
* AI clip generation
* auto-tagging
* tactical event indexing
* player-specific highlights
* subtitle generation
* mobile offline downloads

These are extensions and NOT core requirements.
