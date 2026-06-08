---
stepsCompleted: ['step-01-document-discovery', 'step-02-prd-analysis', 'step-03-epic-coverage-validation', 'step-04-ux-alignment', 'step-05-epic-quality-review', 'step-06-final-assessment']
documentInventory:
  scope: "video-module"
  prd: "_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/prd.md"
  prd_addendum: "_bmad-output/planning-artifacts/prds/prd-javatemplate-2026-05-29/addendum.md"
  architecture: "_bmad-output/planning-artifacts/architecture.md"
  epics: "_bmad-output/planning-artifacts/video-module/epics.md"
  ux: null
---

# Implementation Readiness Assessment Report

**Date:** 2026-05-29
**Project:** javatemplate
**Scope:** Video Module

## Document Inventory

| Document Type | File | Notes |
|---|---|---|
| PRD | `prds/prd-javatemplate-2026-05-29/prd.md` | Authoritative project PRD (May 29) |
| PRD Addendum | `prds/prd-javatemplate-2026-05-29/addendum.md` | Supplemental PRD content |
| Architecture | `architecture.md` | Full architecture (May 25) |
| Epics & Stories | `video-module/epics.md` | Video module epics (May 29) |
| UX Design | *(none)* | Not applicable for this module |

---

## PRD Analysis

### Functional Requirements

| ID | Feature Area | Requirement Summary |
|---|---|---|
| FR-1 | Upload Management | `VideoService.initializeUpload()` available; QuotaProvider.check() called before Upload Session creation |
| FR-2 | Upload Management | QuotaProvider.reserve() called after check passes; reservation handle stored on Upload Session |
| FR-3 | Upload Management | Video content uploaded directly to provider via signed URL; module MUST NOT proxy video payloads |
| FR-4 | Upload Management | Upload Session → COMMITTED and Video → PROCESSING on webhook or client confirmation; idempotent |
| FR-5 | Upload Management | Upload Sessions expire after configurable TTL; QuotaProvider.release() called on expiry |
| FR-6 | Upload Management | File size, MIME type, and container format validated before Upload Session creation; defaults configurable via properties |
| FR-7 | Upload Management | Upload initialization rate-limited per caller; limits configurable via properties |
| FR-8 | Video Lifecycle | Module maintains Operational State: UPLOADING, PROCESSING, READY, FAILED, DELETED |
| FR-9 | Video Lifecycle | Module maintains Access State: ACTIVE, BLOCKED, ARCHIVED |
| FR-10 | Video Lifecycle | Playback eligible ONLY when operationalState == READY AND accessState == ACTIVE |
| FR-11 | Video Lifecycle | DELETED is terminal; any attempt to transition away from DELETED is rejected |
| FR-12 | Video Lifecycle | FAILED Videos can retry upload; new Upload Session created on existing Video; Video ID stable across retries |
| FR-13 | Playback Authorization | All playback MUST use signed Playback Tokens; no unsigned public URLs via any service path |
| FR-14 | Playback Authorization | Playback eligibility gate (FR-10) checked before issuing any token |
| FR-15 | Playback Authorization | Tokens must: expire, be cryptographically signed, include issuedAt + unique token ID; SHOULD include userId, sessionId, expiration |
| FR-17 | Playback Authorization | Token TTL default 15 min, max 2 hours; both configurable via Spring properties; TTL validated server-side |
| FR-18 | Playback Authorization | Token revocation on password change, account suspension, explicit logout, or security incident; consuming app triggers via viewerId |
| FR-19 | Provider Abstraction | Provider Adapter interface defined in infrastructure.video; platform.video has no direct provider SDK imports |
| FR-20 | Provider Abstraction | Required adapter operations: initializeUpload, getAssetStatus, generatePlaybackUrl, deleteAsset, verifyWebhook |
| FR-21 | Provider Abstraction | Optional adapter operations: archiveAsset, restoreAsset |
| FR-22 | Provider Abstraction | All providers must support: direct uploads, signed playback URLs, webhooks, adaptive streaming, asset deletion |
| FR-23 | Provider Abstraction | Bunny.net is MVP provider; provider selection configurable via Spring properties |
| FR-24 | Processing & Reconciliation | Webhook payloads verified via verifyWebhook() before any state transition |
| FR-25 | Processing & Reconciliation | Reconciliation Worker polls provider for Videos in non-terminal states; module NOT solely reliant on webhooks |
| FR-26 | Processing & Reconciliation | Webhook and polling-based event processing must be idempotent |
| FR-27 | Processing & Reconciliation | Reconciliation rules: orphan assets → recovery record (deny playback); missing assets → mark FAILED |
| FR-28 | Processing & Reconciliation | Reconciliation Worker must complete a full cycle and resolve discrepancies within 5 minutes |
| FR-29 | Integration Contract | QuotaProvider interface in platform.video.contract: check, reserve, commit, release; module fails fast if no bean registered; NoOpQuotaProvider available as explicit opt-in |
| FR-30 | Integration Contract | QuotaProvider contract docs must state that implementations are responsible for concurrent-safe enforcement |
| FR-31 | Integration Contract | Admin service layer: Video Access State overrides, Upload Session inspection, Reconciliation Worker manual trigger |
| FR-32 | Integration Contract | Admin REST endpoint in platform.video.api; secured with @PreAuthorize using SecurityConstants |
| FR-33 | Video Lifecycle | Visibility attribute tracked: PRIVATE, GROUP, UNLISTED |
| FR-34 | Video Lifecycle | Module stores Visibility but does NOT gate playback on it; consuming app enforces Visibility semantics |
| FR-35 | Playback Optimization | *(MVP Out of Scope)* HLS adaptive bitrate streaming via active provider |
| FR-36 | Playback Optimization | *(MVP Out of Scope)* Optional enhancements: thumbnail generation, preview scrubbing, subtitle tracks, chapter markers |

**Note:** FR-16 does not appear in the PRD (gap in numbering between FR-15 and FR-17).

**Total FRs in scope for MVP:** 32 (FR-1 through FR-34, minus FR-16 which is absent)

---

### Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-1 | Performance | Upload initialization latency < 500ms p99 |
| NFR-2 | Performance | Playback token generation latency < 200ms p99 |
| NFR-3 | Performance | Upload service availability 99.9% |
| NFR-4 | Performance | Playback service availability 99.95% |
| NFR-5 | Performance | Reconciliation cycle completion ≤ 5 minutes |
| NFR-6 | Security | All playback URLs must be signed or provider-secured; no unsigned public URLs |
| NFR-7 | Security | All webhook payloads must have signatures validated before any state transition |
| NFR-8 | Security | Upload initialization must be rate-limited |
| NFR-9 | Security | Playback providers SHOULD enforce allowed origins/domains where supported |
| NFR-10 | Security | Raw provider credentials and API keys MUST NOT appear in logs or API responses |
| NFR-11 | Reliability | Background jobs MUST use dead-letter queues for failed events |
| NFR-12 | Reliability | All background job processing MUST be retry-safe and idempotent |
| NFR-13 | Reliability | Module MUST support horizontal scaling |
| NFR-14 | Observability | Module services MUST use @Observed(name="...") annotations per javatemplate convention |

**Total NFRs:** 14

---

### Additional Requirements & Constraints

- Upload Session TTL configurable via Spring properties (feature-specific NFR in §4.1)
- Upload validation bounds (file size, MIME types, container formats) configurable via Spring properties with documented defaults
- Rate limit values configurable via Spring properties (defaults TBD at implementation)
- PlaybackToken records MUST be persisted in the application database; revocation implemented via `revokedAt` field
- Module MUST fail fast at startup if no QuotaProvider bean is registered
- Data model fields defined in addendum (A5): Video, UploadSession, PlaybackToken entities with full field specifications

---

### PRD Completeness Assessment

The PRD is well-structured and detailed. Key observations:
- **FR-16 is MISSING from the PRD** — gap in numbering between FR-15 and FR-17; the epics document has back-filled this as a SHOULD requirement for Playback Token claims
- **FR-37 is referenced** in PRD §7.2 ("FR-35 through FR-37 — out of scope for MVP") but is never defined anywhere in the document
- **FR-35/36 explicitly deferred** from MVP scope in §7.2, yet the epics include both in scope
- **Assumptions well-documented** in §9 — 8 explicit assumptions flagged
- **Data model defined** in addendum A5 — comprehensive field-level reference
- **Provider interface signatures** defined in addendum A6

---

## Epic Coverage Validation

### Coverage Matrix

| FR | PRD Requirement Summary | Epic Coverage | Status |
|---|---|---|---|
| FR-1 | Upload initialization with QuotaProvider.check | Epic 2, Story 2.2 | ✓ Covered |
| FR-2 | QuotaProvider.reserve + handle stored on UploadSession | Epic 2, Story 2.2 | ✓ Covered |
| FR-3 | Direct provider upload; no backend proxy | Epic 2, Story 2.2 | ✓ Covered |
| FR-4 | Upload confirmation via webhook OR client signal | Epic 2 Story 2.3 + Epic 4 Story 4.1 | ✓ Covered |
| FR-5 | Upload Session TTL expiry + QuotaProvider.release | Epic 2, Story 2.3 | ✓ Covered |
| FR-6 | File size, MIME, format validation; configurable defaults | Epic 2, Story 2.1 | ✓ Covered |
| FR-7 | Rate limiting per caller on initializeUpload | Epic 2, Story 2.2 | ✓ Covered |
| FR-8 | Operational states: UPLOADING, PROCESSING, READY, FAILED, DELETED | Epic 3, Story 3.1 | ✓ Covered |
| FR-9 | Access states: ACTIVE, BLOCKED, ARCHIVED | Epic 3, Story 3.1 | ✓ Covered |
| FR-10 | Playback eligibility gate: READY AND ACTIVE only | Epic 3, Story 3.1 | ✓ Covered |
| FR-11 | DELETED is terminal; reject transitions away from DELETED | Epic 3, Story 3.1 | ✓ Covered |
| FR-12 | FAILED retry with new UploadSession on same Video entity | Epic 3, Story 3.1 | ✓ Covered |
| FR-13 | Signed playback only; no unsigned URLs through any path | Epic 3, Story 3.2 | ✓ Covered |
| FR-14 | Eligibility gate checked before token issuance | Epic 3, Story 3.2 | ✓ Covered |
| FR-15 | Token: expiry, signed, issuedAt, unique ID | Epic 3, Story 3.2 | ✓ Covered |
| FR-16 | *(MISSING FROM PRD — Added in epics)* Token SHOULD claims: userId, sessionId, expiration | Epic 3, Story 3.2 | ⚠️ Epics-only — PRD gap |
| FR-17 | Token TTL 15 min default, 2 hr max; server-side validation | Epic 3, Story 3.2 | ✓ Covered |
| FR-18 | Token revocation on security events via viewerId | Epic 3, Story 3.3 | ✓ Covered |
| FR-19 | VideoProviderAdapter interface in infrastructure.video | Epic 1, Story 1.3 | ✓ Covered |
| FR-20 | Required adapter operations: 5 methods | Epic 1, Story 1.3 | ✓ Covered |
| FR-21 | Optional adapter operations: archiveAsset, restoreAsset | Epic 1, Story 1.3 | ✓ Covered |
| FR-22 | Provider capability baseline | Epic 1, Story 1.3 | ✓ Covered |
| FR-23 | Bunny.net as MVP; provider via property | Epic 1, Story 1.3 | ✓ Covered |
| FR-24 | Webhook validated via verifyWebhook before state transition | Epic 4, Story 4.1 | ✓ Covered |
| FR-25 | Reconciliation Worker polling non-terminal Videos | Epic 4, Story 4.2 | ✓ Covered |
| FR-26 | Idempotent event processing | Epic 4, Story 4.1 | ✓ Covered |
| FR-27 | Reconciliation rules: orphaned asset → recovery record; missing asset → FAILED | Epic 4, Story 4.2 | ⚠️ Partial — see Gap #3 |
| FR-28 | Reconciliation cycle complete + discrepancies resolved ≤ 5 min | Epic 4, Story 4.2 | ✓ Covered (no perf test AC) |
| FR-29 | QuotaProvider interface; fail-fast startup; NoOpQuotaProvider explicit opt-in | Epic 1, Story 1.2 | ✓ Covered |
| FR-30 | QuotaProvider concurrency contract in documentation | Epic 1, Story 1.2 | ✓ Covered |
| FR-31 | Admin service layer: state overrides, session inspection, manual reconcile | Epic 5, Story 5.1 | ✓ Covered |
| FR-32 | Admin REST endpoint; @PreAuthorize with SecurityConstants | Epic 5, Story 5.1 | ✓ Covered |
| FR-33 | Visibility: PRIVATE, GROUP, UNLISTED | Epic 1, Story 1.1 | ✓ Covered |
| FR-34 | Visibility stored; module does NOT gate playback on it | Epic 1, Story 1.2 (package-info) | ✓ Covered |
| FR-35 | HLS adaptive bitrate via provider | Epic 3, Story 3.2 | ⚠️ Scoped in epics; OUT OF SCOPE in PRD §7.2 |
| FR-36 | Optional media enhancements (thumbnails, subtitles) | Epic 5, Story 5.3 | ⚠️ Scoped in epics; OUT OF SCOPE in PRD §7.2 |

**NFR Coverage:**

| NFR | Category | Epic | Story | Status |
|---|---|---|---|---|
| NFR-1 | Performance: upload init < 500ms p99 | Epic 2 | 2.2 (constraint only) | ⚠️ No verifying AC |
| NFR-2 | Performance: playback token < 200ms p99 | Epic 3 | 3.2 (constraint only) | ⚠️ No verifying AC |
| NFR-3 | Availability: upload 99.9% | Epic 4 | Operational SLA | ✓ Operational |
| NFR-4 | Availability: playback 99.95% | Epic 4 | Operational SLA | ✓ Operational |
| NFR-5 | Reconciliation ≤ 5 min | Epic 4 | Story 4.2 | ✓ Covered |
| NFR-6 | Signed URLs only | Epics 1, 3 | Stories 1.3, 3.2 | ✓ Covered |
| NFR-7 | Webhook signature validation | Epic 4 | Story 4.1 | ✓ Covered |
| NFR-8 | Rate limiting on upload init | Epic 2 | Story 2.2 | ✓ Covered |
| NFR-9 | Provider allowed origins/domains SHOULD | Epics 1, 3 | Not found in story ACs | ⚠️ No implementing AC |
| NFR-10 | No credentials in logs | Throughout | Story 5.2 + individual stories | ✓ Covered |
| NFR-11 | DLQ for failed background job events | Epic 4 | Story 4.1 | ✓ Covered |
| NFR-12 | Idempotent background job processing | Epic 4 | Stories 4.1, 4.2 | ✓ Covered |
| NFR-13 | Horizontal scaling via SKIP LOCKED | Epic 4 | Stories 4.1, 4.2 | ✓ Covered |
| NFR-14 | @Observed annotations | Epic 5 | Story 5.2 | ✓ Covered |

---

### Missing / Partial Requirements

#### 1. FR-16 Defined in Epics but NOT in PRD (Numbering Gap)
**Impact:** The PRD has a deliberate or accidental gap between FR-15 and FR-17. The epics document has created FR-16 as: "Playback Tokens SHOULD include userId, sessionId, and expiration claims." While this is a reasonable SHOULD requirement, it is not formally in the PRD.
**Recommendation:** Add FR-16 to the PRD explicitly, or confirm it was intentionally omitted and remove it from the epics' requirements inventory.

#### 2. FR-37 Referenced but Never Defined
**Impact:** PRD §7.2 reads "Playback optimization features (FR-35 through FR-37)" — implying three features, but only FR-35 and FR-36 are defined. FR-37 is undefined everywhere.
**Recommendation:** Either define FR-37 (likely a third playback optimization feature) or correct the PRD range to "FR-35 through FR-36."

#### 3. FR-35 & FR-36 Scope Conflict: PRD says OUT OF SCOPE; Epics says IN SCOPE
**Impact:** The PRD §7.2 explicitly excludes FR-35 (adaptive streaming) and FR-36 (thumbnails, subtitles) from MVP. The epics include them: FR-35 in Epic 3 Story 3.2, FR-36 in Epic 5 Story 5.3. This creates an unresolved scope disagreement between the two documents.
**Recommendation:** A deliberate scope decision must be made. If FR-35 is included (HLS is part of the core Bunny.net generatePlaybackUrl call, so it's near-zero cost to include), update PRD §7.2. If FR-36 is deferred, remove Story 5.3 from the epic. A clear decision avoids team confusion during implementation.

#### 4. FR-27 Orphaned Asset Detection: Automated Coverage is Incomplete
**Impact:** FR-27 requires the Reconciliation Worker to detect assets that "exist at the provider but have no local state" and create a recovery record. Story 4.2's AC only covers this for admin-triggered reconciliation: "Given a Bunny.net asset exists with no corresponding Video record, When detected during admin-triggered reconciliation..." The automated worker only iterates over local Videos in non-terminal states — it structurally cannot detect an asset at the provider that has no local Video record.
**Recommendation:** Either (a) add a story AC in 4.2 that describes how the automated worker periodically audits provider asset IDs against the local database (e.g., by listing recent provider assets and checking for missing local records), or (b) explicitly acknowledge in the story that automatic orphan detection is limited to admin-triggered runs and update the PRD to match.

#### 5. NFR-1 & NFR-2 Performance Targets Have No Verifying Acceptance Criteria
**Impact:** Upload init < 500ms p99 and playback token < 200ms p99 are called "implementation constraints" in the coverage map. No story AC validates these targets.
**Recommendation:** Add performance test ACs to Stories 2.2 and 3.2, or explicitly defer performance validation to a separate performance testing phase. If deferred, this should be documented as a known post-implementation requirement.

#### 6. NFR-9 Provider Allowed Origins/Domains: No Implementing AC
**Impact:** NFR-9 (SHOULD) requires playback providers to enforce allowed origins/domains where supported. No story AC implements or tests this for the Bunny.net adapter.
**Recommendation:** Add an AC to Story 1.3 or 3.2 for `BunnyVideoProviderAdapter.generatePlaybackUrl()` to include the allowed-origins configuration where Bunny.net CDN token supports referrer restriction, or explicitly mark this as a SHOULD that is deferred to consuming app configuration.

#### 7. Story 3.3 Revocation Window: Undocumented PRD Enhancement
**Impact:** Story 3.3 introduces a "revocation window" concept — after a viewer's tokens are revoked, `authorizePlayback()` blocks new token issuance for that viewer for a configurable window (default 24 hours). This is NOT in FR-18, which only requires that revoked tokens be denied. Blocking new issuance for 24 hours post-revocation is a significant policy decision not approved in the PRD.
**Recommendation:** Either add this behavior to the PRD as an explicit requirement (it is a sound security design), or remove this AC from Story 3.3 if it was not intentionally designed. This should not be silently implemented beyond the PRD specification.

---

### Coverage Statistics

- Total PRD FRs in scope for MVP: 34 (FR-1 to FR-34, plus FR-16 added by epics)
- PRD FRs fully covered: 32
- PRD FRs with partial/disputed coverage: 2 (FR-27 orphan detection gap, FR-16 PRD gap)
- FRs included in epics beyond PRD MVP scope: 2 (FR-35, FR-36)
- Total NFRs: 14
- NFRs fully covered: 11
- NFRs with weak/missing ACs: 3 (NFR-1, NFR-2, NFR-9)
- **Functional coverage rate: 94%** (32/34 FRs fully covered)
- **NFR coverage rate: 79%** (11/14 NFRs with verifying ACs)

---

## UX Alignment Assessment

### UX Document Status

Not Found — intentionally absent.

### Assessment

No UX documentation is required. The PRD explicitly states this module has no UI surface (§4.6: "Out of Scope: End-user REST controllers"). The epics confirm: "N/A — This is a backend platform module with no UI surface."

User journeys (UJ-1 through UJ-3) describe consuming app developers and end-user interactions mediated through the consuming application's own REST layer — not through this module directly.

The only REST endpoints defined for this module are:
- A webhook receiver (`POST /api/video/webhooks/bunny`) — infrastructure, not user-facing
- Admin endpoints (`/api/video/admin/...`) — operator-facing, extension points for consuming apps

**No UX/UI warnings.** Absence of UX documentation is correct and expected for this module type.

---

## Epic Quality Review

### Epic Structure Validation

#### A. User Value Focus

| Epic | Title | Value Assessment | Verdict |
|---|---|---|---|
| Epic 1 | Module Foundation, Bunny.net Adapter & Integration Contract | Technical title; "Delivers" clearly states: app developers can wire the module, connect to Bunny.net, and register QuotaProvider on day one. Primary persona is App Developer. | ✓ Acceptable for platform module |
| Epic 2 | Video Upload Pipeline | "Apps can accept video uploads from end users" — clear user outcome | ✓ User-value focused |
| Epic 3 | Video Lifecycle & Playback Authorization | "PlaybackService.authorizePlayback() is fully functional — tokens issued, revoked, denied with diagnostics" — clear outcome | ✓ User-value focused |
| Epic 4 | Processing Reliability & Reconciliation | "The module is fault-tolerant. Missed webhooks recovered within 5 minutes" — reliability as a value proposition for App Developers | ✓ Acceptable for infrastructure epic |
| Epic 5 | Admin Layer, Observability & Production Readiness | "Operators have full visibility and control over videos in production" — operational value | ✓ Acceptable |

#### B. Epic Independence

| Chain | Dependency | Valid? |
|---|---|---|
| Epic 1 → standalone | No dependencies | ✓ |
| Epic 2 → Epic 1 | Requires VideoProviderAdapter, QuotaProvider, schema | ✓ Proper |
| Epic 3 → Epic 1 | Requires entities + test infra; seeds Video records directly in tests | ✓ Proper |
| Epic 4 → Epic 1 + 2 | Requires VideoProviderAdapter (Epic 1) + Video records in non-terminal states (Epic 2) | ✓ Proper |
| Epic 5 → Epics 1–4 | Explicitly states this dependency | ✓ Proper |

No circular dependencies. No forward dependencies between epics. ✓

#### C. Database/Entity Creation Timing

Story 1.1 creates **three** Flyway migrations upfront: V15 (videos), V16 (upload_sessions), V17 (playback_tokens).

- `videos` — needed in Story 1.1 ✓
- `upload_sessions` — not needed until Epic 2, Story 2.2 ⚠️ Early creation
- `playback_tokens` — not needed until Epic 3, Story 3.2 ⚠️ Early creation

Stories 4.1 and 4.2 correctly create their tables when first needed (V18, V19). ✓

The early creation of `upload_sessions` and `playback_tokens` deviates from the "create tables when first needed" best practice, but for a brownfield module scaffold, keeping all core entity migrations together in one foundation story is a pragmatic and common pattern. **Flagged as minor concern** — not blocking.

---

### Story Quality Assessment

#### Story 1.1: Module Scaffold, Database Schema & Configuration

- Scope: Scaffold, 3 Flyway migrations, 3 entities, 3 repositories, 7-error exception hierarchy, i18n for 3 languages, BaseVideoIT test infrastructure
- **🟡 Concern: Oversized story.** This story is a foundation mega-story. Creating the exception hierarchy, i18n messages (3 languages), and the full WireMock test infrastructure in a single story is substantial. A future developer estimates this at 2–3 days of work. The risk is that it blocks all other Epic 1 work. Consider splitting: Story 1.1a (scaffold, entities, Flyway schema) and Story 1.1b (exception types, i18n keys, BaseVideoIT). Not a blocker — this pattern is common for module scaffolds.
- ACs: Well-structured Given/When/Then. Error coverage is comprehensive. ✓

#### Story 1.2: QuotaProvider Interface & Integration Contract

- Clean, focused, appropriately sized. ✓
- Concurrency contract documentation AC is present and specific. ✓
- Fail-fast startup test and NoOpQuotaProvider wiring explicitly covered. ✓

#### Story 1.3: VideoProviderAdapter Interface & Bunny.net Implementation

- Focused and well-specified. ✓
- WireMock-backed unit test ACs cover all 5 required operations. ✓
- Signed URL enforcement is explicit (NFR-6 compliance verified at story level). ✓
- **🟡 Minor gap:** No AC for `BunnyVideoProviderAdapter.getAssetStatus()` mapping all Bunny.net integer status values to `AssetStatus` enum. Test AC mentions this but doesn't enumerate the expected mappings. Implementation risk: unknown Bunny.net status integers could silently become incorrect `AssetStatus` values.

#### Story 2.1: Upload Validation Chain

- Clean chain pattern, extensible by design. ✓
- Notes that client-provided MIME is advisory; server-side is authoritative. ✓ (aligns with FR-6)

#### Story 2.2: Upload Initialization & Quota Management

- Full sequence covered: validate → check → reserve → create Video → create UploadSession → call provider → return credentials. ✓
- Quota release in `finally` block explicitly required. ✓ (prevents stranded reservations)
- Rate limit rejection before quota check is correct. ✓
- **🟡 Minor gap:** No AC for what happens when `VideoProviderAdapter.initializeUpload()` throws after the UploadSession DB insert has already committed. The `finally` block would call `QuotaProvider.release()`, but the UploadSession record (PENDING) and Video record (UPLOADING) remain persisted. Is this a stuck state? The expiry scheduler will clean these up — but this edge case should be explicitly addressed.

#### Story 2.3: Upload Confirmation & Session Expiry

- Covers PENDING → COMMITTED, duplicate idempotency, and expiry path. ✓
- SKIP LOCKED for multi-node safety. ✓
- `QuotaProvider.release()` called OUTSIDE `@Transactional`. ✓ (correct ordering)
- **🔴 CRITICAL ISSUE: Missing UPLOADING → FAILED transition in Story 3.1.** Story 2.3 explicitly sets `Video.operationalState = FAILED` on session expiry. However, Story 3.1's valid transitions list does NOT include `UPLOADING → FAILED`. Valid transitions listed: `UPLOADING → PROCESSING, PROCESSING → READY, PROCESSING → FAILED, FAILED → UPLOADING`. If `VideoLifecycleService.transitionOperationalState()` is called for `UPLOADING → FAILED`, it would throw `TerminalStateViolationException`. If the expiry scheduler bypasses `transitionOperationalState()` and sets the state directly, it violates the state machine. Either way this is a contradiction between Story 2.3 and Story 3.1 that WILL cause an implementation bug.
- **🟡 Minor gap:** No AC for `confirmUpload()` called when Video exists but has no UploadSession record (not PENDING, not EXPIRED, simply absent). Edge case but possible in a brownfield context.

#### Story 3.1: Video State Machine & FAILED Retry

- State machine coverage is good but incomplete (see critical issue above).
- FAILED retry correctly preserves the original Video ID. ✓
- Retry on non-FAILED video correctly throws 422. ✓
- Access state change on DELETED video correctly throws 409. ✓
- **🔴 CRITICAL ISSUE (same as above):** Valid operational state transitions must include `UPLOADING → FAILED` to support the expiry scheduler from Story 2.3. Omission will cause a runtime failure during the most common failure scenario (abandoned upload TTL expiry).

#### Story 3.2: Playback Token Issuance

- FR-10, FR-13, FR-14, FR-15, FR-16, FR-17 all covered in ACs. ✓
- JWT signed with HMAC-SHA256, claims specified. ✓
- TTL capped at max-ttl. ✓
- Unsigned URL rejection explicit. ✓ (NFR-6)
- FR-35 (HLS via generatePlaybackUrl) implicitly covered via Bunny.net returning an HLS manifest URL. ✓

#### Story 3.3: Playback Token Revocation

- Revocation by viewerId, setting revokedAt on active tokens. ✓ (FR-18)
- validateToken() checks both revokedAt and expiresAt. ✓
- **🟠 Major concern: Undocumented "revocation window" blocking new token issuance.** The AC adds: "before issuing a new token, the service checks whether viewerId has any PlaybackToken with revokedAt IS NOT NULL AND revokedAt > NOW() - revocation-window (configurable, default 24 hours) and if so, throws PlaybackDeniedException." This is a policy-level security decision: after a revocation, the viewer cannot get new tokens for 24 hours. This is NOT specified in FR-18 (which only requires that revoked tokens be denied). This introduces an undocumented property (`revocation-window`), new blocking behavior, and requires consuming apps to understand it. If unintentional, it should be removed. If intentional, it must be added to the PRD.

#### Story 4.1: Webhook Processing Pipeline & Dead-Letter Queue

- Outbox pattern with V18 Flyway migration. ✓
- SKIP LOCKED for horizontal safety. ✓
- DLQ via `FAILED` status + `max-attempts`. ✓ (NFR-11)
- Idempotency via UNIQUE constraint on `event_id`. ✓ (FR-26)
- **🟡 Minor concern: `@PreAuthorize("permitAll()")` on webhook endpoint.** The AC requires this annotation with a code comment that HMAC verification is the auth mechanism. This is the correct approach, but it creates a non-standard security pattern in the codebase — all other endpoints use JWT-based `@PreAuthorize` with SecurityConstants. The comment is documented in the AC, but consuming apps reviewing admin resource extension points may not expect this. Suggest also adding a note in the package-info or Javadoc of the webhook resource.

#### Story 4.2: Reconciliation Worker

- SKIP LOCKED for horizontal safety. ✓
- Transient exception handling (skip, retry next cycle). ✓
- STATE_CORRECTED, MISSING_ASSET incidents logged. ✓
- **🟠 Major gap: Automated orphan detection is impossible without inverse provider scan.** The Reconciliation Worker iterates over local Videos in non-terminal states. By definition, a fully orphaned asset (provider has it; no local Video record exists) will NEVER be found by this worker. The AC handles this as "detected during admin-triggered reconciliation." FR-27 states the Reconciliation Worker MUST apply this rule — the current design limits it to admin-triggered runs only. This is a known architectural limitation that should either be accepted (update PRD) or addressed with an additional scheduled provider-list scan.
- Performance SLA (≤ 5 minutes) noted in AC but no test measures it. ✓ Acceptable — operational SLA.

#### Story 5.1: Admin Service Layer & REST Endpoint

- All FR-31 operations covered: access state override, delete, session inspection, manual reconcile. ✓
- `deleteVideo()` calls `QuotaProvider.release()` for PENDING sessions. ✓
- Non-final classes for extensibility. ✓ (good design)
- `@PreAuthorize` and `@Observed` explicit on every method. ✓

#### Story 5.2: Observability Instrumentation

- Named `VideoMetrics` constants defined. ✓ (good: avoids magic strings)
- 8 metrics specified: latency timers, queue depth gauges, error counter. ✓
- MDC context fields: videoId, ownerId, viewerId, operation, provider. ✓
- Credentials, payloads, secrets never logged. ✓ (NFR-10)
- **🟡 Minor concern:** Story 5.2 depends on Epics 1–5 being complete before adding `@Observed` annotations. This means observability instrumentation cannot be tested incrementally per epic. Annotations could be added in each epic's stories at low cost. Not a blocker but a process quality note.

#### Story 5.3: Optional Media Enhancements

- Backward-compatible interface extension via default methods. ✓
- `UnsupportedOperationException` wrapped by `VideoMediaService` → 502. ✓
- `getThumbnailUrl` is deterministic, no extra API call. ✓
- Caption track via Bunny.net captions API. ✓
- **Note:** This story covers FR-36 which is OUT OF SCOPE for MVP per PRD §7.2. The scope conflict flagged in the coverage section applies here — if this story is implemented, it contradicts the PRD's MVP boundary.

---

### Best Practices Compliance Checklist

| Epic | User Value | Independence | Story Sizing | No Fwd Deps | Tables When Needed | Clear ACs | FR Traceability |
|---|---|---|---|---|---|---|---|
| Epic 1 | ✓ | ✓ | ⚠️ Story 1.1 large | ✓ | ⚠️ Early V16/V17 | ✓ | ✓ |
| Epic 2 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Epic 3 | ✓ | ✓ | ✓ | ✓ | ✓ | ⚠️ Story 3.3 gap | ✓ |
| Epic 4 | ✓ | ✓ | ✓ | ✓ | ✓ | ⚠️ Story 4.2 orphan gap | ✓ |
| Epic 5 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

---

### Quality Findings Summary

#### 🔴 Critical Violations

**QC-1: UPLOADING → FAILED transition missing from Story 3.1 state machine**
- Stories affected: 2.3, 3.1
- The upload session expiry scheduler (Story 2.3) transitions `Video.operationalState` from `UPLOADING → FAILED`. This transition is NOT listed in Story 3.1's valid transition set.
- If the expiry scheduler calls `VideoLifecycleService.transitionOperationalState()`, it will throw `TerminalStateViolationException` at runtime. If it bypasses the service and sets the field directly, it violates the state machine pattern.
- **Fix required:** Add `UPLOADING → FAILED` to Story 3.1's valid transition set, and confirm the expiry scheduler uses `VideoLifecycleService.transitionOperationalState()` rather than direct repository mutation.

#### 🟠 Major Issues

**QM-1: Story 3.3 "Revocation Window" — Undocumented PRD enhancement**
- Story 3.3 adds a configurable revocation window (default 24 hours) that blocks new token issuance for a viewer after their tokens have been revoked. FR-18 does not require this.
- Adds a new undocumented property, new blocking behavior, and a consuming-app surprise.
- **Decision required:** Add to PRD as a specified requirement, or remove from Story 3.3.

**QM-2: FR-27 orphaned asset detection cannot be automated without a provider-asset scan**
- The Reconciliation Worker polls local Videos — it cannot find assets at the provider that have no local Video record. Story 4.2 limits orphan detection to admin-triggered reconciliation.
- FR-27 requires the Reconciliation Worker to apply this rule.
- **Decision required:** Either add a scheduled provider-asset-list scan to Story 4.2, or update PRD FR-27 to acknowledge this limitation.

#### 🟡 Minor Concerns

**Qm-1: Story 1.1 is oversized** — Creates 3 tables, 3 entities, exception hierarchy, i18n (3 languages), and full test infrastructure. Consider splitting.

**Qm-2: Upload_sessions and playback_tokens tables created early** — V16 and V17 are created in Story 1.1 but not consumed until Epic 2 and Epic 3 respectively. Minor deviation from "create tables when needed" principle.

**Qm-3: Story 2.2 — no AC for provider failure after UploadSession persisted** — Edge case: what happens when the provider call fails after DB commit? The `finally` block releases quota, but UPLOADING Video and PENDING UploadSession records remain. Expiry scheduler will clean them up — but this should be an explicit AC.

**Qm-4: Story 2.3 — no AC for confirmUpload() when Video has no UploadSession** — Should return a well-defined error (not NPE or 500).

**Qm-5: Story 4.1 webhook endpoint `@PreAuthorize("permitAll()")` is non-standard** — Requires a code comment explaining it per the AC. A Javadoc on the resource class would improve discoverability.

**Qm-6: Story 5.2 adds @Observed after all Epics complete** — Observability cannot be incrementally verified per epic. Low risk but reduces incremental confidence.

---

## Summary and Recommendations

### Overall Readiness Status

> **🟠 NEEDS WORK — Conditionally Ready**

The Video Module planning artifacts are well-structured, comprehensively specified, and show strong FR traceability. The 14 stories are logically ordered with proper epic sequencing and no circular dependencies. However, **one critical implementation bug and two open design decisions** must be resolved before implementation begins.

---

### Issues Requiring Action Before Implementation Starts

#### 🔴 MUST FIX — Will cause runtime bugs

| ID | Location | Issue | Fix |
|---|---|---|---|
| QC-1 | Story 3.1 + Story 2.3 | `UPLOADING → FAILED` is not in Story 3.1's valid transition set, but Story 2.3's expiry scheduler performs this transition. Will throw `TerminalStateViolationException` in production on every abandoned upload TTL expiry. | Add `UPLOADING → FAILED` to Story 3.1's valid transitions. Confirm expiry scheduler routes through `VideoLifecycleService.transitionOperationalState()`. |

#### 🟠 MUST DECIDE — Design decisions with downstream impact

| ID | Location | Issue | Options |
|---|---|---|---|
| QM-1 | Story 3.3 + PRD FR-18 | Revocation window blocks new token issuance for 24 hours after revocation. Not in FR-18. Adds undocumented property + consuming-app surprise. | (a) Add to PRD as FR-18 amendment. (b) Remove from Story 3.3. |
| QM-2 | Story 4.2 + PRD FR-27 | Automated orphan detection (provider has asset; no local Video record) is architecturally impossible in the current Reconciliation Worker design. Story limits this to admin-triggered runs only. FR-27 implies automatic recovery. | (a) Add a scheduled provider-asset-list audit to Story 4.2. (b) Update PRD FR-27 to acknowledge the limitation. |

#### 🟡 SHOULD RESOLVE — Documentation and spec clarity

| ID | Location | Issue | Action |
|---|---|---|---|
| PRD-1 | PRD §4.2 | FR-16 absent from PRD (gap between FR-15 and FR-17); epics define it as a SHOULD requirement | Add FR-16 to PRD, or explicitly confirm omission and remove from epics inventory |
| PRD-2 | PRD §7.2 | FR-37 referenced but never defined | Define FR-37 or fix range to "FR-35 through FR-36" |
| PRD-3 | PRD §7.2 vs Epics | FR-35 (adaptive streaming) and FR-36 (media enhancements) marked Out of Scope in PRD but included in epics. FR-35 is near-zero cost via HLS URL; FR-36 adds Story 5.3. | Align both documents with an explicit scope decision |
| NFR-1/2 | Stories 2.2, 3.2 | Performance targets (upload init < 500ms, token gen < 200ms p99) have no verifying ACs | Either add performance test ACs or explicitly defer to a post-implementation performance phase |
| NFR-9 | Story 1.3 | No AC implements provider allowed-origins/domains SHOULD requirement | Add AC to Story 1.3 or explicitly defer as consuming-app configuration |
| Qm-3/4 | Stories 2.2, 2.3 | Two edge case ACs missing: (a) provider failure after UploadSession persisted; (b) `confirmUpload()` with no UploadSession for an existing Video | Add ACs to close potential NPE or 500 paths |

---

### Recommended Next Steps

1. **Fix QC-1 immediately** in `video-module/epics.md` — add `UPLOADING → FAILED` to Story 3.1's valid transitions and update the expiry scheduler AC in Story 2.3 to confirm it routes through `VideoLifecycleService`. This is a one-line fix to the epics document.

2. **Decide on QM-1 (revocation window)** — review with the team whether 24-hour new-issuance blocking is desired behavior. If yes, update the PRD. If no, remove the AC from Story 3.3. This is a 15-minute decision with clear downstream impact on consuming-app integration.

3. **Decide on QM-2 (orphan detection scope)** — decide whether automated orphan detection is in MVP scope. If yes, add a provider-asset-list-scan AC to Story 4.2. If no, update PRD FR-27 with an explicit note that automated orphan detection is limited to admin-triggered runs.

4. **Resolve FR-35/FR-36 scope** — update either the PRD or the epics so both documents agree on MVP scope boundaries for adaptive streaming and media enhancements.

5. **Address minor AC gaps** (Qm-3, Qm-4) — add two ACs to Stories 2.2 and 2.3 covering provider failure after DB commit and missing UploadSession. Low effort, high value for implementation confidence.

---

### Final Note

This assessment identified **14 issues** across **4 categories**: 1 critical implementation bug, 2 major design decisions, 5 PRD/scope clarifications, and 6 minor concerns. The critical issue (QC-1) and two major decisions (QM-1, QM-2) should be resolved before the first implementation story begins. The remaining items are recommended improvements that can be addressed in parallel.

The module's overall planning quality is high: the FR inventory is complete and traceable, story ACs are well-structured and error-path aware, the architecture correctly separates platform from infrastructure, and the test infrastructure plan (BaseVideoIT + WireMock) is production-grade. Once the three blocking items above are resolved, this module is ready for implementation.

---

*Assessment completed: 2026-05-29*
*Assessor: BMad Implementation Readiness Skill*
*Report: `_bmad-output/planning-artifacts/implementation-readiness-report-2026-05-29.md`*
