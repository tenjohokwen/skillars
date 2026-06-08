# Addendum — Video Storage & Streaming Module

_Technical depth, implementation options, and rejected alternatives that belong in downstream artifacts or do not fit the PRD narrative._

---

## A1. Provider Comparison

| Provider | Positioning | Notes |
|---|---|---|
| **Bunny.net** | Cost-optimized | MVP target. CDN-native, competitive storage/delivery pricing. Strong for high-volume video delivery. |
| **Mux** | Simplest DX | Best developer experience; highest cost at scale. Good for prototyping or low-volume apps. |
| **Cloudflare Stream** | Flexible / integrated | Strong if already in Cloudflare ecosystem. Per-minute pricing. |
| **AWS Media Services** | Maximum flexibility | Most operational overhead. Best when AWS-native infrastructure is already in place. |

---

## A2. Recommended Backend Stack

- **Database:** PostgreSQL (Flyway migrations, Hibernate 6, Spring Data JPA)
- **Cache / token store:** Redis (for Playback Token revocation lists if stateless JWT approach is chosen — see OQ-4)
- **Background jobs:** Spring `@Scheduled` workers in `platform.video.service`
- **Queue / DLQ:** Queue-backed workers with dead-letter queues for webhook and reconciliation events
- **Provider uploads:** Object-based direct uploads (client → provider; no backend proxy)

---

## A3. HLS Configuration for Coaching / Tactical Review

Recommended HLS configuration for use cases requiring precise seeking (coaching, tactical analysis, frame-by-frame review):

- Streaming format: HLS
- Segment duration: 2 seconds
- Keyframe interval: frequent (≤ 2 seconds)

This is a provider-level configuration. App developers configure these values on their Bunny.net (or equivalent) stream, not in the module.

---

## A4. QuotaProvider Race Condition — Implementation Guidance

The `QuotaProvider` contract requires implementations to be concurrent-safe. Recommended approaches:

- **Transactional quota reservation** — wrap `check()` + `reserve()` in a single database transaction with row-level locking on the quota record.
- **Atomic compare-and-swap** — use a CAS update (`UPDATE quota SET used = used + ? WHERE id = ? AND used + ? <= limit`) and treat 0 rows updated as a denial.
- **Pessimistic locking** — `SELECT FOR UPDATE` on the quota row; safe but reduces throughput under high concurrency.
- **Redis atomic increment** — use `INCRBY` + `EXPIRE` for quota reservation in Redis; appropriate when quota windows are time-bounded (e.g., per-day upload limits).

The module does not prescribe which mechanism to use. The choice depends on the consuming app's datastore and quota semantics.

---

## A5. Data Model — Full Field Reference

### Video

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `ownerId` | String / UUID | Application-level owner reference; semantics defined by consuming app |
| `provider` | Enum / String | Active provider identifier (e.g., `BUNNY`, `MUX`) |
| `providerAssetId` | String | Provider's reference to the media asset |
| `operationalState` | Enum | UPLOADING, PROCESSING, READY, FAILED, DELETED |
| `accessState` | Enum | ACTIVE, BLOCKED, ARCHIVED |
| `title` | String | |
| `description` | String | Nullable |
| `durationMs` | Long | Populated after transcoding completes |
| `storageBytes` | Long | Actual bytes stored after upload; may differ from `reservedBytes` because reservation precedes confirmation |
| `visibility` | Enum | PRIVATE, GROUP, UNLISTED |
| `createdAt` | Timestamp | |
| `updatedAt` | Timestamp | |

### UploadSession

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `videoId` | UUID | FK → Video |
| `providerUploadId` | String | Provider's reference to the upload job |
| `status` | Enum | PENDING, COMMITTED, EXPIRED |
| `reservedBytes` | Long | Bytes reserved via QuotaProvider |
| `reservationHandle` | String | Opaque handle returned by `QuotaProvider.reserve()` |
| `expiresAt` | Timestamp | |
| `createdAt` | Timestamp | |

### PlaybackToken

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key / token identifier claim |
| `videoId` | UUID | FK → Video |
| `viewerId` | String / UUID | Identifies the viewer the token was issued for |
| `expiresAt` | Timestamp | |
| `revokedAt` | Timestamp | Null if not revoked |
| `createdAt` | Timestamp | |

---

## A6. Provider Adapter Interface — Detailed Method Signatures

_Design-level signatures for reference during architecture. The implementation team should refine them._

Provider selection is via Spring property (`video.provider.active`). Adding a new provider requires implementing this interface in `infrastructure.video` only — no changes to `platform.video`.

```
interface VideoProviderAdapter {

    // Required operations
    UploadCredentials initializeUpload(String fileName, long fileSizeBytes);
    AssetStatus getAssetStatus(String providerAssetId);
    SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims);
    void deleteAsset(String providerAssetId);
    WebhookEvent verifyWebhook(String payload, String signature);

    // Optional operations (default: throw UnsupportedOperationException)
    default void archiveAsset(String providerAssetId) { throw new UnsupportedOperationException(); }
    default void restoreAsset(String providerAssetId) { throw new UnsupportedOperationException(); }
}
```

---

## A7. Rejected Alternatives

### teamId in Video data model (→ replaced by ownerId only)

Including `teamId` directly on the Video entity would couple the module to a "team" concept that differs between apps (coaching teams, social groups, workspace organizations). Removing it and delegating group association to the consuming app keeps the module genuinely generic. Visibility mode `GROUP` (formerly `TEAM`) is retained as a label; access enforcement is delegated to the consuming app.

### Moderation as a module feature

Moderation modes (DISABLED/PRE_PUBLISH/POST_PUBLISH) were in the original requirements spec. They were removed because moderation policy is fundamentally a business decision: a coaching platform may want coach-approval before publishing; a social app may want automated content screening. These differ in mechanism, triggers, and workflow. The module's `accessState == BLOCKED` field provides a sufficient integration point for consuming apps that receive moderation outcomes from an external moderation service.

### Quota as a module-level configurable (option b)

Shipping configurable quota defaults (e.g., `video.quota.max-bytes-per-user=10GB`) via Spring properties would embed business values in the module and require a fork or property override for every app with different limits. The `QuotaProvider` interface approach keeps limit values entirely in the consuming app.
