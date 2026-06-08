---
title: Reconciliation Report — video-storage-requirements_v1_3.md vs PRD + Addendum
created: 2026-05-29
source: requirements/video-storage/video-storage-requirements_v1_3.md
prd: _bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/prd.md
addendum: _bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/addendum.md
---

# Reconciliation Report

## 1. Scope

This report compares every requirement, capability, constraint, and design principle in `video-storage-requirements_v1_3.md` (the source) against `prd.md` and `addendum.md` (the target). It identifies:

- **Gaps** — requirements present in the source that are missing or materially under-specified in the PRD/addendum.
- **Intentional exclusions** — items the source called out as out-of-scope or optional, confirmed against Non-Goals in the PRD.
- **Terminology divergences** — cases where the PRD renamed or restructured a source concept, noted for traceability.

---

## 2. Section-by-Section Trace

### §1 Purpose / Use Cases

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Analysis, review, feedback, team video sharing, mobile playback, timeline scrubbing, annotations | Named in Vision (§1) and user journeys (§2.4). Annotations/scrubbing deferred to Future Extensions (§7.2 out of scope). | Covered |
| Private/team-oriented video access | Visibility modes in FR-33; team semantics delegated to consuming app (§6 Non-Goals). | Covered |
| Scalable direct uploads | FR-3 (no backend proxy). | Covered |
| Secure playback | FR-13, FR-14, FR-15. | Covered |
| Provider portability | FR-19 to FR-23; SM-2. | Covered |
| Asynchronous media processing | FR-24 to FR-28 (webhooks + reconciliation worker). | Covered |
| NOT responsible for: billing, legal compliance, DRM, advanced moderation | §6 Non-Goals explicitly lists all four. | Covered |

---

### §2 Core Design Principles

#### §2.1 Provider Abstraction

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Applications interact only with the Video Module; provider may be swapped without changing application logic | FR-19, FR-23; Vision (§1). | Covered |
| Supported provider examples: Bunny.net, Cloudflare Stream, Mux, AWS Media Services | A1 provider comparison table; §7.2 names all four. | Covered |
| MVP: Bunny.net | FR-23 explicit. | Covered |

#### §2.2 Application-Owned Metadata

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Application DB is source of truth for: ownership, permissions, visibility, team associations, playback authorization, quotas, annotations | Vision (§1); QuotaProvider design (FR-29); Visibility (FR-33/34); Glossary (Owner, Visibility). Team associations and annotations deferred to consuming app / future extensions. | Covered |
| Provider responsible only for: media storage, transcoding, streaming delivery | FR-19; Glossary (Provider). | Covered |

---

### §3 High-Level Architecture

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Upload Service, Playback Service, Provider Adapter, Reconciliation Worker, Background Jobs | All named in §4 features and Glossary. | Covered |

---

### §4 Provider Requirements (Required and Optional Capabilities)

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Direct uploads — REQUIRED | FR-22, FR-3. | Covered |
| Signed playback URLs — REQUIRED | FR-22, FR-13. | Covered |
| Webhooks/events — REQUIRED | FR-22, FR-24. | Covered |
| Adaptive streaming — REQUIRED | FR-22, FR-35. | **GAP (minor)** — FR-22 lists adaptive streaming as a baseline capability requirement for every provider, and FR-35 lists it as a SHOULD. The source marks adaptive streaming as REQUIRED for every provider adapter, whereas the PRD downgrades it to SHOULD at the feature level (FR-35). The provider capability table in FR-22 does list it as required for all implementations, which partially resolves this, but the conflict between FR-22 ("MUST support") and FR-35 ("SHOULD support HLS") is ambiguous and could confuse implementers. |
| Asset deletion — REQUIRED | FR-22, FR-20 (`deleteAsset()`). | Covered |
| Optional: thumbnails, captions, archival storage, DRM | FR-21 covers archival (archiveAsset/restoreAsset); FR-36 covers thumbnails as SHOULD; captions/subtitles in FR-37. DRM in §6 Non-Goals. | Covered |

---

### §5 Provider Adapter Interface

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| `initializeUpload()`, `getAssetStatus()`, `generatePlaybackUrl()`, `deleteAsset()`, `verifyWebhook()` | FR-20 table and A6 detailed method signatures. | Covered |
| Optional: `archiveAsset()`, `restoreAsset()` | FR-21 and A6 default methods. | Covered |

---

### §6 Upload Flow

#### §6.1 Upload Initialization

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| `POST /videos/uploads` example endpoint | Noted as consuming-app responsibility (§4.6, FR-32 Out of Scope note). Module exposes service layer, not the REST controller. | Covered (intentional delegation) |
| Validates upload limits, reserves quota, creates upload session, requests provider upload URL, returns signed upload data | FR-1 through FR-6 cover all five backend steps. | Covered |

#### §6.2 Direct Uploads

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Backend MUST NOT proxy large video payloads | FR-3 explicit; SM-C2 counter-metric enforces it. | Covered |

#### §6.3 Upload Confirmation

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| System MUST support both webhook AND client confirmation | FR-4 explicit: both paths supported. | Covered |

---

### §7 Video Lifecycle

#### §7.1 Operational States

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| UPLOADING, PROCESSING, READY, FAILED, DELETED | FR-8 table. | Covered |
| Only READY videos are playable | FR-10. | Covered |
| FAILED videos may be retried | FR-12. | Covered |
| DELETED is terminal | FR-11. | Covered |

#### §7.2 Access States

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| ACTIVE, BLOCKED, ARCHIVED | FR-9 table. | Covered |
| BLOCKED reasons: coach/admin restriction, moderation, account suspension | FR-9 defines BLOCKED as "Playback denied regardless of Operational State." The source lists three distinct reasons (coach restriction, moderation, account suspension). The PRD does not enumerate these reasons in FR-9 but captures account suspension under FR-18 (token revocation) and moderation in the A7 rejected-alternatives note. The enumeration of BLOCKED reasons from the source is not formally preserved in the PRD. | **GAP (minor)** — The specific enumeration of reasons that may cause BLOCKED state (coach/admin restriction, moderation, account suspension) is not captured in the PRD body. While moderation is addressed in Non-Goals and A7, the coach/admin restriction path is only implied by FR-31 (admin Access State overrides). This is low-severity since FR-31 covers the mechanism, but the rationale is absent from the normative text. |
| ARCHIVED: low-cost storage; playback may require restoration delay | FR-9 defines ARCHIVED accurately. | Covered |

---

### §8 Playback Authorization

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Signed playback URLs, short-lived tokens, provider-secured mechanisms | FR-13. | Covered |
| Unsigned public URLs NOT allowed | FR-13 explicit. | Covered |
| Eligibility gate: READY AND ACTIVE | FR-10, FR-14. | Covered |
| Token MUST: expire, be cryptographically signed, include issuedAt, include unique token id | FR-15. | Covered |
| Token SHOULD include: userId, sessionId, expiration | FR-16. | Covered |
| Default TTL 15 min, maximum 2 hours, configurable | FR-17. | Covered |
| Invalidation on: password change, account suspension, explicit logout, security incidents | FR-18. | Covered |
| Server-side validation before issuing playback URLs | FR-17 assumption note; FR-18 consequence note. | Covered |
| Playback providers SHOULD enforce allowed domains/origins | PRD §5.2 Security NFR: "Playback providers SHOULD enforce allowed origins/domains where the provider supports it." | Covered |

---

### §9 Quota Enforcement

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Per-user quotas, upload size limits | FR-1 (QuotaProvider.check), FR-2 (reserve). | Covered |
| Prevent exceeding allocated storage quotas | FR-1, FR-2. | Covered |
| Quota validation before upload initialization | FR-1 explicit sequence. | Covered |
| Concurrent uploads MUST NOT bypass quota through race conditions | FR-30 (QuotaProvider concurrency contract). | Covered |
| Recommended approaches: atomic DB updates, row-level locking, CAS, transactional reservations | A4 provides all four with implementation detail. | Covered |
| Quota reservations MUST expire automatically | FR-5 (Upload Session expiry + release). | Covered |
| Reservation states: PENDING, COMMITTED, EXPIRED | FR-5, Glossary (Upload Session), A5 data model. | Covered |
| Unused reservations MUST expire automatically | FR-5 explicit. | Covered |

---

### §10 Processing & Reconciliation

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Webhook-based processing | FR-24. | Covered |
| Polling fallback reconciliation | FR-25. | Covered |
| Retry-safe event handling | FR-26 (idempotent). | Covered |
| MUST NOT rely solely on webhooks | FR-25 explicit. | Covered |
| Reconciliation rule: provider has asset, local missing → create recovery record, deny playback until reviewed | FR-27. | Covered |
| Reconciliation rule: provider asset missing → mark FAILED | FR-27. | Covered |

---

### §11 Security Requirements

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Signed playback URLs — REQUIRED | FR-13, §5.2. | Covered |
| Upload validation — REQUIRED | FR-6. | Covered |
| Webhook signature validation — REQUIRED | FR-24, §5.2. | Covered |
| Rate limiting — REQUIRED | FR-7, §5.2. | Covered |
| Upload MUST validate: file size, MIME type, container format | FR-6 explicit. | Covered |
| Client-provided MIME types are advisory only | FR-6 explicit. | Covered |
| Raw provider credentials MUST NOT appear in logs/responses | §5.2 Security NFR. | Covered |

---

### §12 Moderation

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Moderation is OPTIONAL and application-configurable | Addressed in §6 Non-Goals and A7 (Rejected Alternatives). | Covered (intentionally excluded) |
| Supported modes: DISABLED, PRE_PUBLISH, POST_PUBLISH | Not in PRD normative text; A7 explains why these were removed (moderation policy is app-level). BLOCKED access state serves as the integration hook. | Covered (intentionally excluded via A7) |
| Default recommendation: POST_PUBLISH for coaching environments | Not in PRD; coach-specific config detail moved to A3 (HLS coaching config). The moderation default is absent, but since moderation itself is excluded, this is consistent. | Consistent with exclusion |

---

### §13 Playback Optimization

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| HLS streaming | FR-35. | Covered |
| Adaptive bitrate playback | FR-35. | Covered |
| Thumbnails | FR-36. | Covered |
| Preview scrubbing | FR-36. | Covered |
| Chapter markers | FR-37. | Covered |
| Subtitle tracks | FR-37. | Covered |
| Recommended coaching config: HLS, 2-second segments, frequent keyframes | A3 captures all three with rationale. | Covered |
| Benefits for seeking precision, tactical review, frame-by-frame analysis | A3 explicitly lists these benefits. | Covered |

---

### §14 Data Model

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Video: id, ownerId, provider, providerAssetId, videoState, accessState, title, description, durationMs, storageBytes, visibility, createdAt, updatedAt | A5 covers all fields with types and notes. | Covered |
| Video: teamId | **GAP** — The source includes `teamId` as a Video field. The PRD intentionally removes it (A7: "teamId in Video data model → replaced by ownerId only"). The removal is architecturally sound and explained in A7, but `teamId` is not replaced by any equivalent — team association is delegated entirely to the consuming app. This is a deliberate design decision, not an oversight, but it represents a material divergence from the source model that consuming apps targeting a coaching/team use case must understand and handle themselves. The traceability is present (A7), but the PRD does not alert consuming apps in the normative Integration Contract section (§4.6) that they must supply their own team-video linkage. |
| UploadSession: id, videoId, providerUploadId, status, reservedBytes, expiresAt, createdAt | A5 covers all fields; additionally adds `reservationHandle`. | Covered (addendum adds field) |
| PlaybackToken: id, videoId, viewerId, expiresAt, revokedAt, createdAt | A5 covers all fields. | Covered |

---

### §15 Visibility

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| PRIVATE, TEAM, UNLISTED | FR-33. Note: PRD renames TEAM → GROUP in the Glossary definition but A5 data model uses TEAM. This is a terminology inconsistency within the PRD/addendum itself. | **GAP (minor)** — The PRD Glossary (§3) defines Visibility as "PRIVATE, GROUP, or UNLISTED," while A5 data model shows `visibility` enum as "PRIVATE, TEAM, UNLISTED." The source uses TEAM. The PRD has an internal inconsistency: the normative Glossary says GROUP but the data model says TEAM. Downstream artifacts that derive from the Glossary will use GROUP; those that derive from A5 will use TEAM. This must be resolved before implementation. |
| Public video support is optional | §6 Non-Goals ("Public video visibility — optional extension, not in core"); §7.2 Out of Scope for MVP. | Covered |

---

### §16 Reliability Requirements

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Retry-safe webhooks | FR-26, §5.3. | Covered |
| Idempotent processing | FR-26, §5.3. | Covered |
| Background workers | FR-25; A2 stack (Spring @Scheduled). | Covered |
| Dead-letter queues | §5.3 explicit. | Covered |
| Horizontal scaling | §5.3 explicit. | Covered |

---

### §17 Recommended Production Stack

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Provider recommendations: Mux (simplest), Bunny.net (cost), Cloudflare Stream (flexible) | A1 provider comparison table. | Covered |
| Backend stack: PostgreSQL, Redis, Queue workers, Object-based uploads, Event-driven processing | A2 backend stack section. | Covered |

---

### §18 Non-Functional Targets

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Upload availability 99.9% | §5.1 table. | Covered |
| Playback availability 99.95% | §5.1 table. | Covered |
| Upload init latency < 500ms | §5.1 table (p99 qualified). | Covered |
| Playback token generation < 200ms | §5.1 table (p99 qualified). | Covered |
| Upload reconciliation < 5 minutes | §5.1 table and FR-28. | Covered |

---

### §19 Future Extensions

| Source item | PRD/Addendum coverage | Status |
|---|---|---|
| Timeline annotations, comments, AI clip generation, auto-tagging, tactical event indexing, player-specific highlights, subtitle generation, mobile offline downloads | §7.2 Out of Scope for MVP explicitly lists most of these. | Covered |

---

## 3. Summary of Gaps

### GAP-1 (Minor): Adaptive streaming requirement level conflict

- **Source location:** §4 Provider Requirements table — adaptive streaming listed as REQUIRED.
- **PRD location:** FR-22 (provider capability baseline, MUST) vs FR-35 (feature, SHOULD).
- **Impact:** An implementer reading only §4.4 (Provider Abstraction) and FR-35 might treat adaptive streaming as optional at the feature layer, contradicting FR-22's MUST. Recommend aligning FR-35 to reflect that adaptive streaming is required at the provider level but its exposure through module features is provider-dependent.

### GAP-2 (Minor): BLOCKED access state — reasons not enumerated in normative text

- **Source location:** §7.2 — BLOCKED reasons include coach/admin restriction, moderation, account suspension.
- **PRD location:** FR-9 defines BLOCKED as "Playback denied regardless of Operational State" without listing reasons. FR-31 covers admin state overrides; FR-18 covers account suspension via token revocation; A7 covers moderation exclusion.
- **Impact:** Low. The mechanism is fully covered; the rationale taxonomy from the source is not formally preserved. Consuming app developers may not know coach/admin restriction is the expected primary use path for BLOCKED without reading FR-31 carefully. Recommend adding a brief note to FR-9 listing the typical reasons.

### GAP-3 (Significant): teamId removal not flagged in Integration Contract

- **Source location:** §14 Data Model — Video includes `teamId`.
- **PRD location:** A7 explains the removal. No mention in the normative §4.6 Integration Contract or §2 Target User sections.
- **Impact:** Consuming apps building team-oriented features (the primary use case described in §1 of the source) must supply their own team-video linkage. The architectural decision is sound and well-explained in A7, but it creates a meaningful integration burden not flagged in the normative PRD sections. A consuming app developer reading only §4.6 (Integration Contract) would not be warned. Recommend adding a note to §4.6 or §4.7 (Visibility) explicitly stating that team-video association is the consuming app's responsibility and is not stored in the module's Video entity.

### GAP-4 (Minor): TEAM vs GROUP terminology inconsistency within PRD/addendum

- **Source location:** §15 — Visibility mode TEAM.
- **PRD location:** Glossary (§3) uses GROUP; A5 data model uses TEAM.
- **Impact:** Downstream artifacts (architecture, epics, stories) instructed to use Glossary terms verbatim (§0) will use GROUP. The data model shows TEAM. This will produce a naming conflict in implementation. The source uses TEAM. This inconsistency must be resolved before story creation — either the Glossary should be updated to TEAM or the A5 data model should be updated to GROUP.

---

## 4. Intentional Exclusions — Confirmed

| Source exclusion | PRD/Addendum confirmation |
|---|---|
| Subscription billing systems | §6 Non-Goals: "Subscription billing or quota limit values — set by consuming apps via QuotaProvider." Confirmed. |
| Enterprise legal compliance engines | §6 Non-Goals: "Enterprise legal compliance." Confirmed. |
| DRM enforcement | §6 Non-Goals: "DRM enforcement." Confirmed. |
| Advanced moderation policy decisions | §6 Non-Goals: "Moderation policy decisions." A7 provides full rationale. Confirmed. |
| Moderation modes (DISABLED/PRE_PUBLISH/POST_PUBLISH) | A7 Rejected Alternatives — explicitly removed with rationale. BLOCKED access state serves as the hook. Confirmed. |
| Public video visibility (optional in source) | §6 Non-Goals and §7.2 Out of Scope for MVP. Confirmed. |
| teamId on Video entity | A7 Rejected Alternatives — explicitly removed with rationale. Confirmed as intentional design decision (not a gap in coverage). |

All intentional exclusions from the source are confirmed present in the PRD Non-Goals or addendum.

---

## 5. Overall Assessment

The PRD and addendum provide strong coverage of the source requirements. The vast majority of functional requirements, constraints, data model fields, NFRs, and design principles are faithfully represented, in most cases with greater precision and architectural clarity than the source.

Four items require attention before the PRD is finalized:

1. **GAP-4 (TEAM vs GROUP)** — must be resolved before story creation to avoid a naming conflict in implementation.
2. **GAP-3 (teamId / Integration Contract warning)** — should be added to the normative Integration Contract to prevent a surprise for consuming app developers.
3. **GAP-1 (adaptive streaming level conflict)** — minor wording alignment between FR-22 and FR-35.
4. **GAP-2 (BLOCKED reasons)** — low-priority note to add to FR-9 for clarity.

No requirements were silently dropped. All exclusions are deliberate and documented.
