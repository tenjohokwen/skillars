---
stepsCompleted: ['step-01-document-discovery', 'step-02-prd-analysis', 'step-03-epic-coverage-validation', 'step-04-ux-alignment', 'step-05-epic-quality-review', 'step-06-final-assessment']
documentsIncluded:
  - scope: 'File Storage Module'
  - prd: '_bmad-output/planning-artifacts/file-storage-module/prd.md'
  - prd_addendum: '_bmad-output/planning-artifacts/file-storage-module/addendum.md'
  - architecture: '_bmad-output/planning-artifacts/architecture.md'
  - epics: '_bmad-output/planning-artifacts/epics.md'
  - ux: 'SKIPPED - not available'
---

# Implementation Readiness Assessment Report

**Date:** 2026-05-25
**Project:** javatemplate
**Scope:** File Storage Module

---

## PRD Analysis

### Functional Requirements

FR-01.01 (Interface): The module exposes `StorageService` supporting `Put`, `Get`, `Delete`, `Exists`, `Stat`, and `Copy`.
FR-01.02 (Implementation): Support `S3StorageService`, `LocalFileSystemStorageService`, and `ReplicatedStorageService`.
FR-01.03 (Provider Config): All settings are externalized via YAML, allowing seamless provider migration.
FR-02.01 (Async Backup): Backup replication is asynchronous, triggered after primary upload success.
FR-02.02 (Durable Outbox): Replication jobs persist in a database-backed outbox.
FR-02.03 (Deletion Flow): Mandatory physical deletion flow: soft-delete → retention → primary delete → backup delete.
FR-02.04 (Replication Toggle): Replication can be independently enabled or disabled via configuration without code changes.
FR-03.01 (Signed URLs): Pre-signed URL generation for client-side uploads/downloads, externally configurable TTL.
FR-03.02 (Key Generation): Centralized `StorageKeyGenerator` enforcing `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}`.
FR-03.03 (Cache Busting): Naming strategy must inherently support cache busting via UUID-based keys.
FR-04.01 (Pre-storage Validation): Enforce MIME type, extension, size, and checksum before upload.
FR-04.02 (Bucket Isolation): One bucket per application/environment.
FR-04.03 (Sanitization): Strict path and filename sanitization to prevent traversal and injection attacks.
FR-04.04 (Encryption): Mandatory TLS for transit; support for Server-Side Encryption (SSE) at rest.
FR-04.05 (Pluggable Validation): Support for optional hooks like Image Verification and Antivirus Scanning.
FR-05.01 (Extended Metadata): Support for original filename, content-type, checksum, custom tags, and tenant identifiers.
FR-05.02 (Multipart Upload): Native support for multipart upload protocols to handle large files efficiently.
FR-06.01 (Observability): Structured logs, metrics (latency, error rates, queue depth, size distribution), and OTel tracing.
FR-06.02 (Testing): MinIO-in-Docker for CI integration; `LocalFileSystemStorageService` for unit tests.

**Total FRs: 19** (across 6 groups: FR-01 through FR-06)

### Non-Functional Requirements

NFR-01 (Reliability): Exponential backoff/retries for all network-bound operations.
NFR-02 (Performance): Streaming via `InputStream`/`OutputStream` to handle large files; no memory-loading of file content.
NFR-03 (Portability): Decoupled from business logic and provider-specific APIs.
NFR-04 (Scalability): Horizontal scaling for replication workers.

**Total NFRs: 4**

### Additional Requirements (from Addendum)

ADD-01 (Streaming Mandate): Heavy reliance on `InputStream` and `OutputStream` is mandatory — reinforces NFR-02.
ADD-02 (Outbox State Machine): Status-driven table with states: PENDING → PROCESSING → COMPLETED/FAILED.
ADD-03 (Config Consistency): YAML-based configuration using consistent keys across all storage implementations.
ADD-04 (Timeout Config): Externally configurable request and connection timeouts mandatory for all network-bound operations.
ADD-05 (Migration Utility): Support for object-copy tooling — streaming read from Provider A, write to Provider B — as a gateway utility.
ADD-06 (Metadata Portability): Utility support for metadata export/import to ensure portability during provider migration.
ADD-07 (Local Dev Parity): `LocalFileSystemStorageService` must mirror the directory structure of the cloud bucket strategy.
ADD-08 (Safe Sequencing): Orphan prevention ordering: Upload object → Store DB reference → Commit transaction.

### Architectural Decisions / Constraints

DEC-01: Bucket versioning explicitly disabled (reduce operational cost/complexity).
DEC-02: Soft deletes excluded from storage module — handled by the calling application's database logic.
DEC-03: One-bucket-per-application-per-environment for strict logical isolation.
DEC-04: Encryption at rest enabled at the provider level (SSE-S3 or SSE-KMS).
DEC-05: Secrets must be injected via environment variables or secret managers; hardcoding strictly forbidden.
DEC-06: Module provides/extracts metadata during transport; consuming application is responsible for persistence and querying.

### PRD Completeness Assessment

The PRD is well-structured and comprehensive. The requirements are granular and numbered, making traceability straightforward. The addendum adds valuable implementation constraints that narrow the solution space meaningfully. No ambiguous or contradictory requirements were detected. One notable gap: FR-02.03 references "soft-delete" in the deletion flow, but DEC-02 explicitly excludes soft deletes from the storage module — this is internally consistent (the soft-delete step belongs to the calling app), but the deletion flow description could be clearer about where module responsibility begins.

---

## Epic Coverage Validation

### Coverage Matrix

| FR Number | PRD Requirement (Summary) | Epic / Story Coverage | Status |
|---|---|---|---|
| FR-01.01 | `StorageService` interface: Put, Get, Delete, Exists, Stat, Copy | Epic 1 / Story 1.2 | ✓ Covered |
| FR-01.02 | S3, LocalFS, and ReplicatedStorageService implementations | Epics 1, 4, 5 / Stories 1.2, 4.1, 5.1 | ✓ Covered |
| FR-01.03 | All provider settings externalized via YAML | Epic 1 / Story 1.1 | ✓ Covered |
| FR-02.01 | Async backup replication triggered after primary upload success | Epic 4 / Story 4.1 | ✓ Covered |
| FR-02.02 | Replication jobs persist in `outbox_replication_jobs` | Epic 4 / Story 4.2 | ✓ Covered |
| FR-02.03 | Deletion flow: soft-delete → retention → primary delete → backup delete | Epic 3 / Story 3.2 | ✓ Covered |
| FR-02.04 | Replication toggle via config (`app.storage.replication.enabled`) | Epic 4 / Story 4.1 | ✓ Covered |
| FR-03.01 | Pre-signed URL for uploads and downloads with configurable TTL | Epics 2, 3 / Stories 2.2, 3.1 | ✓ Covered |
| FR-03.02 | `StorageKeyGenerator` enforcing `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` | Epic 1 / Story 1.2 | ✓ Covered |
| FR-03.03 | UUID-based key for inherent cache busting | Epic 1 / Story 1.2 | ✓ Covered |
| FR-04.01 | MIME type, extension, size, checksum validation before upload | Epic 2 / Story 2.1 | ✓ Covered |
| FR-04.02 | One bucket per application/environment | Epic 1 / Story 1.1 | ✓ Covered |
| FR-04.03 | `FilenameSanitizationStep` — path traversal / injection prevention | Epic 2 / Story 2.1 | ✓ Covered |
| FR-04.04 | TLS in transit; SSE at rest via provider config | Epic 1 / Story 1.2 | ✓ Covered |
| FR-04.05 | Pluggable optional validation hooks (image verify, antivirus) | Epic 2 / Story 2.1 | ✓ Covered |
| FR-05.01 | Extended metadata: filename, content-type, checksum, custom tags, tenant | Epic 2 / Story 2.3 | ⚠️ Partial — custom tags (JSONB) not explicit in AC |
| FR-05.02 | Multipart upload via `S3TransferManager` (threshold: 8 MB) | Epic 2 / Stories 1.2, 2.3 | ✓ Covered |
| FR-06.01 | Metrics, structured logs, OTel tracing via `@Observed` | Epic 5 / Story 5.3 | ✓ Covered |
| FR-06.02 | MinIO-in-Docker for CI; `LocalFileSystemStorageService` for unit tests | Epics 1, 5 / Stories 1.3, 5.2 | ✓ Covered |
| NFR-01 | Exponential backoff/retries via `@Retryable` | Epic 1 / Story 1.2 | ✓ Covered |
| NFR-02 | Streaming via `InputStream`/`OutputStream`; no memory-loading | Epic 1 / Story 1.2 | ✓ Covered |
| NFR-03 | Provider decoupling; stream-copy migration utility | Epic 5 / Story 5.4 | ✓ Covered |
| NFR-04 | `SELECT ... FOR UPDATE SKIP LOCKED` for horizontal scaling | Epic 4 / Story 4.2 | ✓ Covered |
| ADD-01 | Streaming mandate enforced throughout | Epics 1–5 | ✓ Covered |
| ADD-02 | Outbox state machine: PENDING → PROCESSING → COMPLETED/FAILED | Epic 4 / Story 4.2 | ✓ Covered |
| ADD-03 | YAML config consistency across all providers | Epic 1 / Story 1.1 | ✓ Covered |
| ADD-04 | Externally configurable request and connection timeouts | **NOT FOUND** | ❌ MISSING |
| ADD-05 | Provider migration streaming copy utility | Epic 5 / Story 5.4 | ✓ Covered |
| ADD-06 | Metadata export/import utility for portability | Epic 5 / Story 5.4 | ✓ Covered |
| ADD-07 | `LocalFileSystemStorageService` mirrors cloud bucket directory structure | Epic 5 / Story 5.1 | ✓ Covered |
| ADD-08 | Safe sequencing: Upload → DB insert → Commit (orphan prevention) | Epic 2 / Story 2.3 | ✓ Covered |

### Missing Requirements

#### Critical Missing Requirement

**ADD-04 — Externally Configurable Timeouts**
- PRD/Addendum text: "Externally configurable request and connection timeouts are mandatory for all network-bound operations."
- Impact: Without this, the S3 SDK will use its default (often unbounded) timeouts, risking thread exhaustion and silent hangs under network degradation in production.
- Story 1.1 lists many `app.storage.*` YAML keys in its AC, but `request-timeout` and `connection-timeout` are absent. No other story mentions them.
- Recommendation: Add `app.storage.s3.request-timeout-ms` and `app.storage.s3.connection-timeout-ms` to Story 1.1's configuration key list and `StorageProperties`, and wire them into `S3Client` bean creation in Story 1.2.

#### Notable Gaps (Partial Coverage / Implementation Risk)

**FR-05.01 — Custom Tags (JSONB) Missing from Story 2.3 AC**
- Story 2.3's AC lists the extended metadata committed on confirm: `originalFilename, contentType, sizeBytes, checksum, tenantId, provider, bucket, uploadConfirmedAt`. Custom tags (JSONB) — referenced in the requirements inventory under FR-05.01 — are absent.
- Risk: The developer implementing Story 2.3 has no explicit instruction to include the JSONB tags column in the DB insert or in `SignUploadRequest`, so it will likely be silently omitted.
- Recommendation: Amend Story 2.3 AC to include `tags JSONB` in the `file_storage_objects` insert. Also add a `tags` field to `SignUploadRequest` so custom tags can be provided by the client at sign-time. Ensure Story 1.1 schema migration includes the column.

**FR-02.03 / Story 4.2 — Outbox Poller Does Not Address Deletion Jobs**
- `outbox_replication_jobs` is used for both replication (Epic 4) and backup deletion (Story 3.2). Story 4.2's `OutboxPollerScheduler` AC only describes replication: "calls the backup `StorageService` to replicate the object." There is no `job_type` field in the table definition, and no AC for how the poller handles deletion jobs.
- Risk: An implementer of Story 4.2 will build a poller that only copies objects; backup deletion (required by FR-02.03's final step) will be unimplemented or added ad-hoc, violating the full deletion lifecycle.
- Recommendation: Add a `job_type` column (`ENUM: REPLICATE, DELETE`) to `outbox_replication_jobs` in the V12 migration (Story 1.1). Amend Story 4.2 AC to handle both job types: REPLICATE → copy object to backup; DELETE → call delete on backup provider.

**FR-04.01 / Story 2.1 — Checksum Validation Semantics at Sign-Time Are Ambiguous**
- Story 2.1 includes `ChecksumValidationStep` in the pre-sign validation chain. At sign-time, the file has not yet been uploaded — the actual checksum cannot be verified against real content. The confirm-time validation in Story 2.3 correctly checks the real ETag.
- Risk: A developer implementing `ChecksumValidationStep` may misunderstand this as validating actual content integrity (impossible at sign-time), leading to either a broken or vestigial step.
- Recommendation: Add a note to Story 2.1's AC clarifying that `ChecksumValidationStep` at sign-time validates format only (e.g., SHA-256 hex string of correct length), not content integrity. Add a cross-reference to Story 2.3 where actual content integrity is verified.

### Coverage Statistics

- Total PRD FRs: 19 | Covered: 18 | Partially covered: 1 (FR-05.01) | Missing: 0
- FR coverage rate: **100%** (with 1 incomplete AC)
- Total NFRs: 4 | **100% covered**
- Additional requirements: 8 | Covered: 7 | Missing: 1 (ADD-04)
- Additional requirements coverage rate: **87.5%**

**Overall Assessment: Strong structural coverage. 1 hard requirement missing from all ACs (ADD-04), 3 targeted AC gaps that pose real implementation risk.**

---

## UX Alignment Assessment

### UX Document Status

Not applicable. The epics document explicitly states: *"N/A — This is a backend infrastructure module with no UI surface."* No UX documentation exists and none is required. The module exposes REST endpoints consumed programmatically; all client-facing file I/O flows directly between the client and S3 via pre-signed URLs.

### Alignment Issues

None — no UX surface to validate.

### Warnings

None. UX is not implied by the PRD or architecture for this module.

---

## Epic Quality Review

### Epic Structure Validation

#### User Value Check

| Epic | Title | User Value Assessment | Verdict |
|---|---|---|---|
| Epic 1 | Storage Module Foundation | Technical foundation — no end-user value. Developer value only (module compiles, S3 connects). | ⚠️ Technical milestone |
| Epic 2 | Secure Upload Gateway | Applications can accept file uploads via pre-signed URLs. Clear application value. | ✓ Pass |
| Epic 3 | File Access & Lifecycle Management | Applications can serve downloads and manage lifecycle. Clear application value. | ✓ Pass |
| Epic 4 | Async Replication & Resilience | Files are durably backed up asynchronously. Clear operational value. | ✓ Pass |
| Epic 5 | Local Provider, Observability & Production Readiness | Developer + operator value: local dev, CI, metrics. Somewhat technical. | ⚠️ Mixed |

**Note on Epic 1:** By strict create-epics-and-stories standards, "Storage Module Foundation" is a technical milestone (no user value). In context, this module has no end-user UI and the "user" is the developer/consuming application, making a foundation epic unavoidable. Flagged as a minor concern, not a blocking violation.

#### Epic Independence Check

| Epic | Can stand alone? | Issues |
|---|---|---|
| Epic 1 | ✓ Yes | None |
| Epic 2 | ✓ Yes (uses Epic 1 output) | None — can be tested with seeded data |
| Epic 3 | ✓ Yes (Epic 1 + 2 as foundation) | Logical data dependency on Epic 2 for real scenarios, but testable with seeded data |
| Epic 4 | ✓ Yes (Epic 1 + 2 as foundation) | None in terms of code, but backup deletion (FR-02.03) only completes when Epic 4 runs — documented below |
| Epic 5 | ✓ Yes (depends on all preceding epics) | Appropriate placement as final epic |

---

### Story Quality Assessment

#### Story 1.1 — Best Practices Compliance Checklist

- [x] Epic delivers developer value (foundation)
- [x] ACs use Given/When/Then format
- [x] ACs are testable and specific
- [ ] **VIOLATION: Tables created when first needed**

Story 1.1 creates three database tables upfront via two migrations:
- V12: `file_storage_objects` (first used: Story 2.3) and `outbox_replication_jobs` (first used: Story 2.3)
- V13: `storage_access_events` (first used: Story 3.1)

By best practices, `outbox_replication_jobs` should be created in Story 2.3 (or the Epic 4 scope where it's actually processed), and `storage_access_events` should be created in Story 3.1.

#### Story 1.2 — Best Practices Compliance

- [x] Delivers compiling, working S3 gateway
- [x] ACs use BDD format ✓
- [x] No forward dependencies ✓
- [x] Streaming enforcement specified ✓
- [x] `S3TransferManager` mandated (no direct `putObject()`) ✓

#### Story 1.3 — Best Practices Compliance

- [x] Developer value: shared test harness ✓
- [x] ACs are specific and testable ✓
- [x] No forward dependencies ✓

#### Story 2.1 — Best Practices Compliance

- [x] Security-first design: `FilenameSanitizationStep` runs first ✓
- [x] Extension point for pluggable validators ✓
- [x] ACs cover both happy path and each failure mode ✓
- [x] Unit tests specified within this story ✓
- [ ] **CONCERN: `ChecksumValidationStep` at sign-time — semantics need clarification (already flagged in Step 3)**

#### Story 2.2 — Best Practices Compliance

- [x] Delivers complete upload URL flow ✓
- [x] ACs cover quota enforcement, validation, error cases ✓
- [x] Integration test verifying real MinIO interaction ✓
- [x] `@Observed` and `@PreAuthorize` specified ✓

#### Story 2.3 — Best Practices Compliance

- [x] Transaction boundary correctly specified (S3 call outside `@Transactional`) ✓
- [x] Idempotency check for duplicate confirms ✓
- [x] `StorageObjectConfirmedEvent` publishing specified ✓
- [ ] **VIOLATION: Missing `tags JSONB` from insert AC (already flagged in Step 3)**
- [x] `@Observed` and `@PreAuthorize` specified ✓

#### Story 3.1 — Best Practices Compliance

- [x] Egress tracking (access events) well-specified ✓
- [x] Integration test verifies real MinIO download ✓
- [x] Soft-delete enforcement (404 for deleted files) ✓

#### Story 3.2 — Best Practices Compliance

- [x] V14 migration is correctly incremental (added `physical_deleted_at`) ✓
- [x] SKIP LOCKED for multi-node safety ✓
- [x] S3 delete call outside `@Transactional` ✓
- [ ] **VIOLATION: Undocumented cross-epic dependency on Epic 4 — see below**

Story 3.2 inserts `outbox_replication_jobs` with status `PENDING` to trigger **backup deletion**. But Story 4.2 (Async Replication Outbox Poller) only specifies handling of **replication** jobs, not deletion jobs. Neither story documents this cross-epic dependency. The backup deletion leg of FR-02.03 cannot be fully exercised until Epic 4's poller is extended to handle deletion jobs — yet Story 3.2 carries no "depends on Epic 4 for full deletion" note.

#### Story 4.1 — Best Practices Compliance

- [x] Replication toggle conditional wiring ✓
- [x] Clear: primary failure → no outbox entry ✓
- [ ] **CONCERN: Ambiguous two-path outbox insertion**

Story 4.1 AC says `ReplicatedStorageService.put` inserts an outbox entry. Story 2.3 also inserts an outbox entry. Story 4.1 then states "the `outbox_replication_jobs` PENDING record inserted in Story 2.3 is the sole trigger for replication — `ReplicatedStorageService` does not write a second outbox entry on confirm." This is only meaningful if `ReplicatedStorageService.put` is somehow invoked during the confirm flow — but it shouldn't be (clients upload directly to S3). The AC is guarding against a non-existent code path, which suggests the design around who creates outbox entries is not clearly articulated in the stories. A code comment at the `ReplicatedStorageService` level would be needed to prevent future confusion.

#### Story 4.2 — Best Practices Compliance

- [x] SKIP LOCKED for horizontal scaling ✓
- [x] Dead-letter handling (FAILED state) ✓
- [x] Guarded by `replication.enabled` flag ✓
- [ ] **VIOLATION: Poller does not handle deletion jobs (already flagged in Step 3)**

#### Story 5.1 — Best Practices Compliance

- [x] Local provider mirrors S3 directory structure ✓
- [x] Streaming enforced ✓
- [ ] **CONCERN: `ValidationChainTest` duplicated from Story 2.1**

Story 5.1's AC references `ValidationChainTest` covering sanitization ordering. Story 2.1 already specifies this test. The specification appears in two stories, creating ambiguity about which story owns this test. Implementers may defer the test thinking "Story 5.1 will handle it."

#### Story 5.2 — Best Practices Compliance

- [x] Comprehensive E2E coverage (sign → upload → confirm → download → delete) ✓
- [x] SKIP LOCKED concurrency test ✓
- [x] Fresh container state requirement ✓

#### Story 5.3 — Best Practices Compliance

- [x] Named constants via `StorageMetrics` class ✓
- [x] MDC context fields specified (key, tenantId, operation, provider) ✓
- [x] No raw credentials/content in logs ✓
- [ ] **VIOLATION: `@Observed` annotations duplicated from earlier stories**

Stories 2.2, 2.3, 3.1, and 3.2 all already specify `@Observed(name = "...")` in their ACs. Story 5.3 then says "each carries a distinct `@Observed` annotation, confirming OTel span creation." This creates ambiguity: if Story 5.3 is where `@Observed` is ADDED, then the earlier stories' ACs are wrong for including it. If Story 5.3 is just verifying what earlier stories added, the earlier stories are correct but Story 5.3 provides no new work. A developer reading Epic 5 in isolation may believe they are responsible for adding `@Observed` during this epic, only to find the earlier stories already included it.

#### Story 5.4 — Best Practices Compliance

- [x] Streaming migration (no `readAllBytes()`) ✓
- [x] Idempotent `importMetadata` ✓
- [x] Integration test with two MinIO containers ✓

---

### Quality Assessment Summary

#### 🔴 Critical Violations
None.

#### 🟠 Major Issues

**Issue Q-1: All database tables created upfront in Story 1.1 (Table Timing Violation)**
- V12 creates `file_storage_objects` and `outbox_replication_jobs` — not needed until Story 2.3.
- V13 creates `storage_access_events` — not needed until Story 3.1.
- Best practice: `outbox_replication_jobs` → create in Story 2.3 (or at the start of Epic 4). `storage_access_events` → create in Story 3.1.
- Mitigation if left as-is: Low implementation risk since Flyway handles ordering. The violation is structural but not blocking. Document the deviation intentionally.

**Issue Q-2: Story 5.3 `@Observed` Annotation Duplication**
- `@Observed` annotations specified in Stories 2.2, 2.3, 3.1, 3.2, AND again in Story 5.3.
- Creates ambiguity: which story is responsible for adding the annotation?
- Recommendation: Remove the `@Observed` verification from Story 5.3 and keep it only in each endpoint story. Story 5.3 should focus solely on Micrometer metrics, MDC logging, and the gauge/counter instrumentation — all of which are genuinely new work in this story.

**Issue Q-3: Story 3.2 Backup Deletion Cross-Epic Dependency Is Undocumented**
- Story 3.2 creates outbox deletion jobs; Story 4.2 must process them, but Story 4.2 only covers replication.
- Neither story documents this dependency.
- Risk: The backup deletion leg of FR-02.03 silently fails until Epic 4 is extended.
- Recommendation: Add an explicit dependency note to Story 3.2 ("Backup deletion processing requires Epic 4 / Story 4.2 to be extended with `job_type` support"). Amend Story 4.2 as described in the Missing Requirements section.

#### 🟡 Minor Concerns

**Concern Q-4: Epic 1 Is a Technical Milestone**
- "Storage Module Foundation" does not describe user-facing value. Acceptable for a pure backend module with no UI surface, but deviates from the spirit of user-centric epics.
- No change required; document as an intentional exception.

**Concern Q-5: `ValidationChainTest` Specified in Both Story 2.1 and Story 5.1**
- Creates ambiguity about test ownership. An implementer may skip the test in Story 2.1 ("Story 5.1 will write it") or skip it in Story 5.1 ("Story 2.1 already wrote it").
- Recommendation: Remove the `ValidationChainTest` AC from Story 5.1. Keep it only in Story 2.1 where the component is built.

**Concern Q-6: Two-Path Outbox Insertion (Story 4.1) Needs Code Documentation**
- `ReplicatedStorageService.put` and the confirm endpoint (Story 2.3) both create outbox entries, but for different code paths (programmatic backend upload vs client-direct upload). Story 4.1's AC warns against duplication without clearly explaining why it's not a risk.
- Recommendation: Add a note to Story 4.1 clarifying the two distinct code paths and why they don't produce duplicate entries for the same file.

---

## Summary and Recommendations

### Overall Readiness Status

> **⚠️ NEEDS WORK** — The planning is architecturally sound and structurally complete, but 8 issues across 2 categories must be addressed before developers begin writing production code. None are blocking from starting Epic 1, but the outbox deletion gap and the missing timeout config will cause feature-level failures if not resolved by the time the affected stories are picked up.

---

### All Issues by Priority

| ID | Severity | Category | Issue | Affects |
|---|---|---|---|---|
| ADD-04 | 🔴 High | Missing Requirement | Timeout config (`request-timeout`, `connection-timeout`) absent from all story ACs | Story 1.1 + 1.2 |
| Q-3 | 🟠 Major | Story Dependency | Story 3.2 backup deletion cross-epic dependency on Epic 4 not documented | Stories 3.2 + 4.2 |
| FR-05.01 | 🟠 Major | Missing AC | Custom tags (JSONB) missing from Story 2.3 AC and Story 1.1 schema | Stories 1.1 + 2.3 |
| Outbox | 🟠 Major | Design Gap | `outbox_replication_jobs` has no `job_type` column; Story 4.2 only handles REPLICATE | Stories 1.1, 3.2, 4.2 |
| Q-2 | 🟠 Major | AC Duplication | `@Observed` specified in Stories 2.2/2.3/3.1/3.2 AND Story 5.3 — ambiguous ownership | Story 5.3 |
| Q-1 | 🟠 Major | Table Timing | `outbox_replication_jobs` and `storage_access_events` created upfront in Story 1.1 | Story 1.1 |
| Q-5 | 🟡 Minor | AC Duplication | `ValidationChainTest` specified in both Story 2.1 and Story 5.1 | Stories 2.1 + 5.1 |
| Q-6 | 🟡 Minor | Documentation | Two-path outbox insertion design not documented in story text | Story 4.1 |

---

### Critical Issues Requiring Immediate Action (Before Any Story Is Started)

**1. Add `job_type` to `outbox_replication_jobs` schema (Resolves: Outbox gap + Q-3)**

This is the most impactful change because it touches Story 1.1 (schema), Story 3.2 (deletion job insertion), and Story 4.2 (poller logic) simultaneously. Doing it after Story 1.1 is implemented would require a new Flyway migration mid-sprint.

Action: Before Story 1.1 is implemented, add `job_type VARCHAR NOT NULL CHECK (job_type IN ('REPLICATE', 'DELETE'))` to the `outbox_replication_jobs` schema in the V12 migration. Update Story 3.2's AC to specify `job_type = 'DELETE'` when inserting deletion jobs. Update Story 4.2's AC to branch on `job_type` — REPLICATE → copy to backup; DELETE → delete from backup.

**2. Add timeout config to Story 1.1 and Story 1.2 (Resolves: ADD-04)**

Action: Add `app.storage.s3.request-timeout-ms` and `app.storage.s3.connection-timeout-ms` to:
- Story 1.1's `app.storage.*` key list and `StorageProperties` record
- Story 1.2's `S3Client` bean creation AC (wire the timeouts into the SDK client builder)

**3. Add custom tags (JSONB) to Story 1.1 schema and Story 2.3 AC (Resolves: FR-05.01)**

Action: Add `tags JSONB` column to `file_storage_objects` in V12 migration (Story 1.1). Add `tags` field to `SignUploadRequest` record and to the `file_storage_objects` insert in Story 2.3's AC.

---

### Recommended Next Steps

1. **Amend Stories 1.1, 2.3, 3.2, 4.2** with the changes described above before the first sprint begins. These four stories form the backbone of the module; fixing them now avoids expensive mid-sprint rework.

2. **Resolve Story 5.3 `@Observed` ambiguity**: Decide the source of truth. Recommended: keep `@Observed` in Stories 2.2, 2.3, 3.1, 3.2. Remove the `@Observed` verification clause from Story 5.3 and focus Story 5.3 on Micrometer metrics, MDC logging, and the replication queue gauge — all of which are genuinely new work.

3. **Clarify `ChecksumValidationStep` at sign-time** in Story 2.1 AC: add one sentence clarifying it validates format only (SHA-256 hex, 64 chars), with a cross-reference to Story 2.3 for actual content integrity verification.

4. **Add dependency note to Story 3.2**: "Note: Backup deletion jobs are processed by Epic 4 / Story 4.2. Backup deletion will not execute in production until Epic 4 is complete."

5. **Deduplicate `ValidationChainTest`**: Remove from Story 5.1 AC; keep in Story 2.1 only.

6. **Table timing (Q-1)**: Low operational risk if left as-is (Flyway handles migration ordering). Acceptable deviation from best practice for this module. If future teams cite this epics doc as a template, consider noting it as an intentional exception.

---

### Final Note

This assessment identified **8 issues** across **2 categories** (requirements coverage + epic quality). The architecture and story sequencing are well-designed — no circular dependencies, no blocking structural violations, and FR coverage is complete at 100%. The gaps are tactical: three ACs need amendments before development starts, and the outbox schema needs one new column. Address items 1–4 in the Next Steps above before the first sprint kicks off; items 5–6 can be handled during backlog refinement.

**Assessment Date:** 2026-05-25
**Assessor:** Implementation Readiness Skill (BMad)
**Report File:** `_bmad-output/planning-artifacts/implementation-readiness-report-2026-05-25.md`
