---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - '_bmad-output/planning-artifacts/file-storage-module/prd.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/file-storage-module/addendum.md'
---

# javatemplate - File Storage Module Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for the File Storage Module, decomposing the requirements from the PRD, Architecture, and Addendum into implementable stories.

## Requirements Inventory

### Functional Requirements

**FR-01: Unified Storage Gateway**
- FR-01.01: The module exposes `StorageService` supporting `Put`, `Get`, `Delete`, `Exists`, `Stat`, and `Copy`.
- FR-01.02: Support `S3StorageService`, `LocalFileSystemStorageService`, and `ReplicatedStorageService` implementations.
- FR-01.03: All provider settings are externalized via YAML, allowing seamless provider migration without code changes.

**FR-02: Resilient Data Management**
- FR-02.01: Backup replication is asynchronous, triggered after primary upload success.
- FR-02.02: Replication jobs persist in a database-backed outbox (`outbox_replication_jobs`).
- FR-02.03: Mandatory physical deletion flow: soft-delete → retention period → primary delete → backup delete.
- FR-02.04: Replication can be independently enabled or disabled via configuration without code changes.

**FR-03: Signed URLs & Key Naming**
- FR-03.01: Pre-signed URL generation for client-side uploads and downloads with externally configurable TTL.
- FR-03.02: Centralized `StorageKeyGenerator` enforcing `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` pattern.
- FR-03.03: Naming strategy must inherently support cache busting via UUID-based keys.

**FR-04: Content Validation & Security**
- FR-04.01: Enforce MIME type, extension, size, and checksum validation before upload (at sign-time and confirm-time).
- FR-04.02: One bucket per application/environment for strict logical isolation.
- FR-04.03: Strict path and filename sanitization to prevent traversal and injection attacks.
- FR-04.04: Mandatory TLS for data in transit; support for Server-Side Encryption (SSE) at rest.
- FR-04.05: Support for pluggable optional validation hooks (Image Verification, Antivirus Scanning).

**FR-05: Metadata & Large Files**
- FR-05.01: Support for extended metadata: original filename, content-type, checksum, custom tags (JSONB), and tenant identifiers.
- FR-05.02: Native support for multipart upload via `S3TransferManager` for large files (threshold: 8 MB).

**FR-06: Observability & Testing**
- FR-06.01: Structured logs, Micrometer metrics (latency, error rates, outbox queue depth, file size distribution), and OTel tracing via `@Observed`.
- FR-06.02: MinIO-in-Docker (Testcontainers) for CI integration tests; `LocalFileSystemStorageService` for unit tests.

### NonFunctional Requirements

- NFR-01 (Reliability): Exponential backoff and retries for all network-bound operations via Spring Retry (`@Retryable` + `@Recover`).
- NFR-02 (Performance): Streaming via `InputStream`/`OutputStream` (and `S3TransferManager`) for all file I/O — no memory-loading (`InputStream.readAllBytes()` is strictly forbidden).
- NFR-03 (Portability): Full decoupling from provider-specific APIs; provider migration via stream-copy utility (Provider A → Provider B).
- NFR-04 (Scalability): Horizontal scaling for replication workers via `SELECT ... FOR UPDATE SKIP LOCKED` on `outbox_replication_jobs`.

### Additional Requirements

**From Architecture — Module Structure:**
- Module resides at `com.softropic.skillars.infrastructure.storage` (business-agnostic; no `platform` dependency).
- Package layers: `api/`, `service/`, `repo/`, `contract/` (DTOs, exceptions, events), `config/`.
- All schema changes via Flyway migrations only — no DDL in Java code. Migrations: `V12__storage_schema.sql`, `V13__storage_access_events.sql`.

**From Architecture — API & Security:**
- REST surface (4 endpoints, all prefixed `/api/storage/`):
  - `POST /api/storage/sign/upload` — validate quota, generate pre-signed PUT URL
  - `POST /api/storage/confirm/{key}` — verify upload, commit `file_storage_objects` record + trigger replication outbox
  - `GET /api/storage/sign/download/{key}` — verify existence, record access event, generate pre-signed GET URL
  - `DELETE /api/storage/{key}` — soft-delete, schedule physical deletion and backup deletion via outbox
- Every endpoint must carry `@PreAuthorize` (using `SecurityConstants`) and `@Observed`.
- No byte proxying through the backend — all file data flows directly between client and S3.

**From Architecture — Transaction Boundary (CRITICAL):**
- S3 client calls must be OUTSIDE `@Transactional`; only DB writes go inside `@Transactional`.
- Safe sequencing: Upload to S3 → verify → DB insert `file_storage_objects` → insert `outbox_replication_jobs` → commit.

**From Architecture — Data & Config:**
- Three DB tables owned exclusively by this module: `file_storage_objects`, `outbox_replication_jobs`, `storage_access_events`.
- Quota enforcement: `SUM(size_bytes)` check against `file_storage_objects` before issuing any signed upload URL.
- All config under `app.storage.*` YAML namespace (timeouts, TTLs, retry counts, bucket names — never hardcoded).
- Error envelope: `StorageErrorCode` enum + `ApplicationException` subclasses + existing `ErrorDto` shape (no `ProblemDetail`).
- i18n keys under `storage.*` namespace.

**From Architecture — Gaps Resolved:**
- Gap 1 (FR-02.03): `DeletionSchedulerService` with `@Scheduled` + SKIP LOCKED; adds `physical_deleted_at` column and `app.storage.deletion.retention-days` config.
- Gap 2 (FR-04.03): `FilenameSanitizationStep` as the first step in `ValidationChain` (strips null bytes, control chars, path traversal sequences, normalizes Unicode NFC, truncates to 255 chars).
- Gap 3 (FR-05.02): `S3TransferManager` for all put operations — `s3Client.putObject()` direct call is forbidden for file uploads.

**From Architecture — Cross-Module:**
- `StorageObjectConfirmedEvent` (Spring `ApplicationEvent`) published after confirmed upload for downstream consumers.
- Egress tracking: every signed download URL issuance logged to `storage_access_events`.

**From Addendum:**
- Provider migration utility: streaming copy from Provider A to Provider B.
- Metadata export/import utility for portability during provider migration.
- `LocalFileSystemStorageService` must mirror the directory structure of the cloud bucket strategy.
- Externally configurable request and connection timeouts for all network-bound operations.

### UX Design Requirements

N/A — This is a backend infrastructure module with no UI surface.

### FR Coverage Map

| Requirement | Epic | Brief |
|---|---|---|
| FR-01.01 | Epic 1 | `StorageService` interface defined |
| FR-01.02 | Epics 1, 4, 5 | S3 in E1, Replicated in E4, LocalFS in E5 |
| FR-01.03 | Epic 1 | `app.storage.*` YAML config fully externalized |
| FR-02.01 | Epic 4 | Async replication triggered post-confirm |
| FR-02.02 | Epic 4 | `outbox_replication_jobs` + `OutboxPollerScheduler` |
| FR-02.03 | Epic 3 | Soft-delete → retention → `DeletionSchedulerService` |
| FR-02.04 | Epic 4 | `app.storage.replication.enabled` toggle |
| FR-03.01 | Epics 2, 3 | Sign-upload in E2, sign-download in E3 |
| FR-03.02 | Epic 1 | `StorageKeyGenerator` established |
| FR-03.03 | Epic 1 | UUID in key pattern |
| FR-04.01 | Epic 2 | `ValidationChain` dual-stage (sign + confirm) |
| FR-04.02 | Epic 1 | Single bucket per app/env in config |
| FR-04.03 | Epic 2 | `FilenameSanitizationStep` as first chain step |
| FR-04.04 | Epic 1 | HTTPS SDK default + SSE at provider level |
| FR-04.05 | Epic 2 | `ValidationStep` interface open for extension |
| FR-05.01 | Epic 2 | `file_storage_objects` record committed on confirm |
| FR-05.02 | Epic 2 | `S3TransferManager` for large file uploads |
| FR-06.01 | Epic 5 | Metrics, structured logs, `@Observed` tracing |
| FR-06.02 | Epic 5 | `BaseStorageIT` + `MinioContainerConfig` |
| NFR-01 | Epic 1 | `@Retryable` on `S3StorageService` from the start |
| NFR-02 | Epic 1 | Streaming pattern established in `S3StorageService` |
| NFR-03 | Epic 5 | Provider migration + metadata export/import utilities |
| NFR-04 | Epic 4 | SKIP LOCKED on `outbox_replication_jobs` |

## Epic List

### Epic 1: Storage Module Foundation
Establishes the module skeleton, database schema, core S3 storage service, and test infrastructure so the module exists and all subsequent stories have a compiling, testable foundation.
**Delivers:** The module compiles, connects to S3, can perform raw put/get/delete/exists/stat/copy operations with proper key naming, retry resilience, and streaming enforced from the start — and integration tests have a shared MinIO+PostgreSQL harness ready to use.
**FRs covered:** FR-01.01, FR-01.02 (S3StorageService only), FR-01.03, FR-03.02, FR-03.03, NFR-01, NFR-02, FR-06.02 (test harness foundation)

### Epic 2: Secure Upload Gateway
Delivers the complete file upload flow: validation, quota enforcement, pre-signed upload URL, and upload confirmation.
**Delivers:** Applications can accept file uploads from clients via pre-signed URLs with full dual-stage validation and quota enforcement.
**FRs covered:** FR-03.01 (upload half), FR-04.01, FR-04.02, FR-04.03, FR-04.04, FR-04.05, FR-05.01, FR-05.02

### Epic 3: File Access & Lifecycle Management
Delivers the download and delete flows, egress tracking, and the physical deletion scheduler.
**Delivers:** Applications can serve file downloads and manage the full file lifecycle through to physical deletion.
**FRs covered:** FR-02.03, FR-03.01 (download half)

### Epic 4: Async Replication & Resilience
Delivers the outbox-based async backup replication, horizontal-safe polling, and replication toggle.
**Delivers:** Files are durably backed up to a secondary provider with resilient async replication that scales horizontally.
**FRs covered:** FR-02.01, FR-02.02, FR-02.04, NFR-04

### Epic 5: Local Provider, Observability & Production Readiness
Delivers `LocalFileSystemStorageService` for local development, the MinIO CI test harness, full observability instrumentation, and the provider portability utilities.
**Delivers:** Developers can run and test locally with production-parity, CI validates the full system against MinIO, and operations can monitor storage via metrics and tracing.
**FRs covered:** FR-01.02 (LocalFS + Replicated complete), FR-06.01, FR-06.02, NFR-02 (enforced via test coverage), NFR-03

---

## Epic 1: Storage Module Foundation

Establishes the module skeleton, database schema, and core S3 storage service so the module exists and can be wired into the application.

### Story 1.1: Module Scaffold, Database Schema & Configuration

As a developer integrating the storage module,
I want the storage module's package structure, database schema, configuration model, error codes, and exception hierarchy to be in place,
So that all subsequent storage stories have a consistent, compiling foundation to build on.

**Acceptance Criteria:**

**Given** the project builds cleanly before this story
**When** the story is implemented
**Then** the package structure `com.softropic.skillars.infrastructure.storage.{api,service,repo,contract,contract/exception,contract/event,config}` exists
**And** `StorageProperties` is annotated `@ConfigurationProperties(prefix = "app.storage")` and bound in `StorageConfig`
**And** `app.storage.*` keys (`provider`, `bucket`, `endpoint-url`, `presign-ttl-seconds`, `upload-ttl-seconds`, `replication.enabled`, `quota.default-bytes`, `poller.fixed-delay-ms`, `poller.batch-size`, `retry.max-attempts`, `retry.backoff-initial-ms`, `retry.backoff-multiplier`, `deletion.retention-days`, `s3.request-timeout-ms`, `s3.connection-timeout-ms`) are present in `application.yml` with sensible defaults

**Given** no storage tables exist
**When** Flyway runs migration `V12__storage_schema.sql`
**Then** table `file_storage_objects` is created with columns including `tags JSONB` for custom metadata (original filename, content-type, checksum, tenant ID, provider, bucket, upload timestamps, and arbitrary key/value tags)
**And** table `outbox_replication_jobs` is created with a non-nullable `job_type VARCHAR CHECK (job_type IN ('REPLICATE', 'DELETE'))` column to distinguish backup-copy jobs from backup-deletion jobs, plus all other columns, indexes, and FK constraints
**And** `V13__storage_access_events.sql` creates the `storage_access_events` table

**Given** a JPA context is available
**When** the entities are loaded
**Then** `FileStorageObject`, `OutboxReplicationJob`, and `StorageAccessEvent` are valid `@Entity` classes with Lombok `@Getter`
**And** `FileStorageObjectRepository`, `OutboxReplicationJobRepository`, and `StorageAccessEventRepository` are Spring Data repositories

**Given** a storage exception is thrown during a request
**When** it propagates to the API layer
**Then** `@ExceptionHandler` methods registered in `ApiAdvice` return the correct `ErrorDto` with the appropriate HTTP status for each of: `StorageObjectNotFoundException` (404), `QuotaExceededException` (429), `StorageValidationException` (422), `StorageProviderException` (502)
**And** `StorageErrorCode` implements `ErrorCode` with values: `STORAGE_OBJECT_NOT_FOUND`, `QUOTA_EXCEEDED`, `UPLOAD_NOT_CONFIRMED`, `VALIDATION_FAILED`, `REPLICATION_FAILED`, `PROVIDER_ERROR`

**Given** the i18n message files exist
**When** a storage error key is looked up
**Then** `storage.objectNotFound`, `storage.quotaExceeded`, `storage.validationFailed`, `storage.uploadNotConfirmed`, `storage.providerError` are present in `messages.properties`, `messages_en.properties`, and `messages_fr.properties`

### Story 1.2: StorageService Interface, S3 Implementation & Key Generation

As a developer building upload, download, and deletion features,
I want a provider-agnostic `StorageService` interface backed by a working `S3StorageService` and a key generator,
So that all higher-level storage features can delegate to a consistent, retryable S3 gateway without knowing provider details.

**Acceptance Criteria:**

**Given** the module scaffold from Story 1.1 is in place
**When** `StorageService` is defined
**Then** it declares methods: `put(String key, InputStream data, long contentLength, String contentType)`, `get(String key): InputStream`, `delete(String key)`, `exists(String key): boolean`, `stat(String key): StorageObjectMetadata`, `copy(String sourceKey, String destinationKey)`
**And** no implementation details or AWS SDK types appear in the interface

**Given** `app.storage.provider=s3` is configured
**When** `StorageConfig` creates beans
**Then** `S3Client`, `S3AsyncClient`, `S3Presigner`, and `S3TransferManager` beans are created using the configured `endpoint-url`, credentials, and region
**And** `S3Client` is configured with `app.storage.s3.request-timeout-ms` as the API call timeout and `app.storage.s3.connection-timeout-ms` as the connection acquisition timeout — never using SDK defaults
**And** `S3StorageService` is wired as the active `StorageService` bean

**Given** `S3StorageService` makes an S3 call that throws a transient error
**When** the call fails fewer than `app.storage.retry.max-attempts` times
**Then** Spring Retry retries the call with exponential backoff using `backoff-initial-ms` and `backoff-multiplier`
**And** the `@Recover` method catches final failure and throws `StorageProviderException` with `StorageErrorCode.PROVIDER_ERROR`
**And** `@Retryable` is applied to all S3 operations: `put`, `get`, `delete`, `exists`, `stat`, `copy`

**Given** file content is uploaded or downloaded via `S3StorageService`
**When** the operation executes
**Then** all file I/O uses streaming (`InputStream`/`OutputStream` or `S3TransferManager`) — `InputStream.readAllBytes()` is never called
**And** `put` operations use `S3TransferManager.upload()` (not `s3Client.putObject()` directly)

**Given** a request to generate a storage key for entity type `"documents"`, entityId `42`, and file extension `"pdf"`
**When** `StorageKeyGenerator.generate(entity, entityId, extension)` is called
**Then** the returned key matches the pattern `documents/42/{yyyy}/{mm}/{uuid}.pdf` where `{yyyy}` and `{mm}` reflect the current date and `{uuid}` is a random UUID
**And** no two calls with the same arguments return the same key (UUID uniqueness guarantees cache-busting)

### Story 1.3: Storage Test Infrastructure

As a developer writing integration tests for the storage module,
I want a shared `BaseStorageIT` base class backed by MinIO and PostgreSQL Testcontainers,
So that all subsequent storage integration tests can inherit a fully wired test context without duplicating container setup.

**Acceptance Criteria:**

**Given** `MinioContainerConfig` is defined as a `@TestConfiguration`
**When** a test class extends `BaseStorageIT`
**Then** a MinIO container is started via Testcontainers, mirroring the pattern of the existing `PostgresContainerConfig`
**And** `app.storage.endpoint-url`, bucket name, access key, and secret key are injected into the Spring context from the container's dynamic properties via `@DynamicPropertySource`
**And** the configured bucket is created in MinIO before the first test runs

**Given** `BaseStorageIT` is annotated `@SpringBootTest` + `@Testcontainers`
**When** any integration test extends it
**Then** both the MinIO container and the PostgreSQL container are available and fully wired into the application context

**Given** `MinioContainerConfig` and `BaseStorageIT` are in place
**When** integration tests in subsequent stories reference `extends BaseStorageIT`
**Then** they compile and receive a fully configured storage context with no additional container setup

---

## Epic 2: Secure Upload Gateway

Applications can accept file uploads from clients via pre-signed URLs with full dual-stage validation and quota enforcement.

### Story 2.1: Pre-Storage Validation Chain

As a developer building the upload endpoint,
I want a pluggable, ordered validation chain that enforces filename safety, MIME type, extension, file size, and checksum rules before any upload is accepted,
So that invalid or malicious files are rejected before a pre-signed URL is ever issued.

**Acceptance Criteria:**

**Given** the module foundation from Epic 1 is in place
**When** `ValidationChain` is invoked with a `ValidationRequest`
**Then** it executes each `ValidationStep` in registration order, stopping on the first failure
**And** a failed step throws `StorageValidationException` with `StorageErrorCode.VALIDATION_FAILED` and a descriptive message

**Given** a filename containing path traversal sequences (`../`, `./`), null bytes, control characters, or Unicode that normalizes unsafely
**When** `FilenameSanitizationStep` (the first step in the chain) processes it
**Then** those sequences are stripped/normalized and the sanitized value replaces `originalFilename` in the request
**And** filenames longer than 255 characters are truncated
**And** this step runs before any other validation step

**Given** a file declared with a MIME type not in the configured allowlist
**When** `MimeTypeValidationStep` runs
**Then** `StorageValidationException` is thrown with a message referencing the rejected MIME type

**Given** a file declared with an extension not in the configured allowlist
**When** `ExtensionValidationStep` runs
**Then** `StorageValidationException` is thrown

**Given** a file declared with `fileSizeBytes` exceeding the configured maximum
**When** `FileSizeValidationStep` runs
**Then** `StorageValidationException` is thrown

**Given** a `SignUploadRequest` with a declared `checksum` field
**When** `ChecksumValidationStep` runs at sign-time
**Then** it validates format only: the value must be a valid SHA-256 hex string (64 lowercase hex characters) — content integrity is NOT verified at this stage because the file has not yet been uploaded
**And** if the format is invalid, `StorageValidationException` is thrown with a message indicating the expected format
**Note:** Actual content integrity (ETag vs declared checksum) is verified in Story 2.3 at confirm-time via `s3Client.headObject`

**Given** the `ValidationStep` interface is defined
**When** a developer adds a new step (e.g., image verification, antivirus)
**Then** implementing `ValidationStep` and registering it as a Spring bean is sufficient to include it in the chain (FR-04.05)

**Given** unit tests for the validation chain
**When** they run
**Then** `ValidationChainTest` covers: each step independently, chain halts on first failure, chain passes when all steps pass, sanitization ordering

### Story 2.2: Pre-signed Upload URL Endpoint

As a client application,
I want to request a pre-signed S3 upload URL with quota validation,
So that I can upload files directly to S3 without routing binary data through the backend.

**Acceptance Criteria:**

**Given** a valid `SignUploadRequest` (contentType, extension, fileSizeBytes, tenantId, checksum, tags)
**When** `POST /api/storage/sign/upload` is called by an authenticated user
**Then** the endpoint passes declared metadata through `ValidationChain` (sign-time validation)
**And** computes `SELECT SUM(size_bytes) FROM file_storage_objects WHERE tenant_id = ?` and rejects with `QuotaExceededException` (HTTP 429) if `(currentUsage + fileSizeBytes) > app.storage.quota.default-bytes`
**And** generates a pre-signed PUT URL via `S3Presigner` with TTL of `app.storage.upload-ttl-seconds`
**And** returns `SignUploadResponse` with `key`, `uploadUrl`, and `expiresAt` (ISO 8601)

**Given** `SignUploadRequest` is missing a required field
**When** `POST /api/storage/sign/upload` is called
**Then** Jakarta validation (`@NotBlank`, `@Positive`, etc.) rejects with HTTP 400 before reaching service logic

**Given** the storage endpoint
**When** any request reaches it
**Then** `StorageResource` is annotated `@Observed(name = "storage.sign.upload")` on the method
**And** `@PreAuthorize` using `SecurityConstants` is present (no unprotected endpoint)

**Given** the quota is not exceeded and validation passes
**When** `StorageKeyGenerator.generate(entity, entityId, extension)` is called
**Then** the returned key follows the `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` pattern
**And** the same key is embedded in both the `uploadUrl` and the `key` field of the response

**Given** an integration test for the upload URL endpoint
**When** it runs against a live MinIO container
**Then** the returned `uploadUrl` accepts a real PUT request to MinIO
**And** quota enforcement rejects a second upload when the first would exceed the configured limit

### Story 2.3: Upload Confirmation Endpoint

As a client application,
I want to confirm that my direct-to-S3 upload succeeded so the system registers the file and triggers backup replication,
So that the file is tracked, quotas are accurate, and downstream consumers are notified.

**Acceptance Criteria:**

**Given** a client has successfully PUT a file to S3 using the URL from Story 2.2
**When** `POST /api/storage/confirm/{key}` is called
**Then** `StorageSigningService` calls `s3Client.headObject(key)` OUTSIDE any `@Transactional` boundary to verify the object exists in S3
**And** actual `ContentType`, `ContentLength`, and `ETag` from HeadObject are compared against the sign-time declaration; mismatch throws `StorageValidationException` (HTTP 422)
**And** if validation passes, a `@Transactional` block inserts a `file_storage_objects` record (with all extended metadata: originalFilename, contentType, sizeBytes, checksum, tenantId, provider, bucket, uploadConfirmedAt, and the `tags` JSONB map supplied in `SignUploadRequest`)
**And** within the same transaction, an `outbox_replication_jobs` record is inserted with `status = PENDING` and `job_type = 'REPLICATE'`
**And** after the transaction commits, `ApplicationEventPublisher.publishEvent(StorageObjectConfirmedEvent)` is called

**Given** the key does not exist in S3
**When** `POST /api/storage/confirm/{key}` is called
**Then** `StorageObjectNotFoundException` is returned (HTTP 404)

**Given** the confirm endpoint is called for a key that already has a `file_storage_objects` record
**When** the duplicate confirmation arrives
**Then** an idempotent check prevents a second insert (return existing `ConfirmUploadResponse`)

**Given** the transaction commits successfully
**When** `ConfirmUploadResponse` is returned
**Then** it contains `id`, `key`, `sizeBytes`, `contentType`, `checksum` (sha256 prefix format), and `uploadedAt` (ISO 8601) — matching the architecture-specified response shape

**Given** the endpoint
**When** any request reaches `StorageResource.confirmUpload`
**Then** it carries `@Observed(name = "storage.confirm.upload")` and `@PreAuthorize`

---

## Epic 3: File Access & Lifecycle Management

Applications can serve file downloads and manage the full file lifecycle through to physical deletion.

### Story 3.1: Pre-signed Download URL Endpoint & Egress Tracking

As a client application,
I want to request a pre-signed S3 download URL for a stored file,
So that I can serve file downloads directly from S3 without routing binary data through the backend, while the system tracks egress for usage reporting.

**Acceptance Criteria:**

**Given** a stored file with key `documents/42/2026/05/uuid.pdf` that has a confirmed `file_storage_objects` record
**When** `GET /api/storage/sign/download/{key}` is called by an authenticated user
**Then** `StorageSigningService` verifies a non-deleted `file_storage_objects` record exists for the key; if absent, throws `StorageObjectNotFoundException` (HTTP 404)
**And** inserts a `storage_access_events` record (`key`, `tenantId`, `sizeBytes`, `timestamp`) for egress tracking before generating the URL
**And** generates a pre-signed GET URL via `S3Presigner` with TTL of `app.storage.presign-ttl-seconds`
**And** returns `SignDownloadResponse` with `key`, `downloadUrl`, and `expiresAt` (ISO 8601)

**Given** the key exists but `deleted_at` is set on the `file_storage_objects` record
**When** `GET /api/storage/sign/download/{key}` is called
**Then** `StorageObjectNotFoundException` is returned (HTTP 404) — soft-deleted files are not downloadable

**Given** the endpoint
**When** any request reaches `StorageResource.signDownload`
**Then** it carries `@Observed(name = "storage.sign.download")` and `@PreAuthorize`

**Given** an integration test for the download endpoint
**When** it runs against a live MinIO container
**Then** the returned `downloadUrl` successfully retrieves the file content directly from MinIO
**And** a `storage_access_events` row is persisted after each successful URL issuance

### Story 3.2: File Deletion & Physical Lifecycle

As a system administrator and consuming application,
I want soft-delete to immediately hide a file from access, and a scheduler to physically remove it from S3 and backup after a configurable retention period,
So that deletions are safe, reversible within the retention window, and eventually free storage resources.

**Acceptance Criteria:**

**Given** a confirmed file with key `documents/42/2026/05/uuid.pdf`
**When** `DELETE /api/storage/{key}` is called by an authenticated user
**Then** the `file_storage_objects` record has `deleted_at` set to the current timestamp (soft-delete)
**And** the file is immediately inaccessible via the download endpoint (returns 404)
**And** HTTP 204 No Content is returned (no body)
**And** the endpoint carries `@Observed(name = "storage.delete")` and `@PreAuthorize`

**Given** Flyway migration `V14__storage_physical_deletion.sql` runs
**When** the schema is updated
**Then** `file_storage_objects` has a new nullable column `physical_deleted_at TIMESTAMP`
**And** an index exists on `(deleted_at, physical_deleted_at)` to support efficient scheduler queries

**Given** `app.storage.deletion.retention-days` is configured (default: 30)
**When** `DeletionSchedulerService` fires on its `@Scheduled(fixedDelay)` interval
**Then** it queries `file_storage_objects WHERE deleted_at < NOW() - retention_days AND physical_deleted_at IS NULL` using `FOR UPDATE SKIP LOCKED` (safe for multi-node deployment)
**And** for each eligible record: calls `StorageService.delete(key)` on the primary provider OUTSIDE `@Transactional`, then within a single `@Transactional` block: inserts an `outbox_replication_jobs` entry with `status = PENDING` and `job_type = 'DELETE'` to trigger backup deletion, and sets `physical_deleted_at` to the current timestamp
**And** if `StorageService.delete` fails, the record is skipped (not marked) and will be retried on the next scheduler run
**Note — cross-epic dependency:** The `job_type = 'DELETE'` outbox jobs created here are processed by `OutboxPollerScheduler` in Epic 4 / Story 4.2. Backup deletion does not execute until Epic 4 is implemented. The primary S3 deletion and `physical_deleted_at` marking are fully functional within this story.

**Given** a file whose `deleted_at` is within the retention window
**When** the scheduler runs
**Then** the file is NOT physically deleted yet

**Given** an integration test
**When** the delete flow is exercised end-to-end
**Then** soft-delete makes the file inaccessible immediately
**And** after simulating the retention period, `DeletionSchedulerService.processDeletions()` removes the object from MinIO and sets `physical_deleted_at`

---

## Epic 4: Async Replication & Resilience

Files are durably backed up to a secondary provider with resilient async replication that scales horizontally.

### Story 4.1: Replicated Storage Service

As a system operator,
I want a replication-aware storage service that automatically queues backup jobs when `replication.enabled=true`,
So that every uploaded file is durably mirrored to a secondary provider without changing any upload-path code.

**Acceptance Criteria:**

**Given** `app.storage.replication.enabled=true` is configured
**When** `StorageConfig` creates the `StorageService` bean
**Then** `ReplicatedStorageService` is wired as the active `StorageService` bean, wrapping `S3StorageService` as the primary delegate
**And** a secondary `StorageService` bean (e.g., a second `S3StorageService` pointing to the backup endpoint) is wired as the replication target — both configured independently via `app.storage.*`

**Given** `app.storage.replication.enabled=false`
**When** `StorageConfig` creates the `StorageService` bean
**Then** `S3StorageService` is wired directly as the active bean (no replication wrapper)

**Given** `ReplicatedStorageService.put(key, data, contentLength, contentType)` is called
**When** the primary `S3StorageService.put` succeeds
**Then** an `outbox_replication_jobs` record with status `PENDING` is inserted for the new `file_storage_objects` record
**And** if the primary put fails, no outbox record is created and the exception propagates

**Given** the confirm-upload flow from Story 2.3
**When** `replication.enabled=true` and the transaction commits
**Then** the `outbox_replication_jobs` PENDING record (job_type='REPLICATE') inserted in Story 2.3 is the sole trigger for replication — `ReplicatedStorageService` does not write a second outbox entry on confirm (no duplicate)
**Note — two distinct code paths:** `ReplicatedStorageService.put` is called when the backend itself programmatically uploads a file (e.g., migration). The client-direct-to-S3 upload flow never calls `ReplicatedStorageService.put`; replication for that path is triggered exclusively by the confirm endpoint's outbox insert. These are separate paths and cannot produce duplicate outbox entries for the same object.

**Given** a unit test for `ReplicatedStorageService`
**When** it runs (using Mockito mocks for both the primary and backup `StorageService`)
**Then** it verifies: primary success → outbox entry created; primary failure → no outbox entry; replication.enabled=false → direct S3StorageService used

### Story 4.2: Async Replication Outbox Poller

As a system operator,
I want an outbox poller that processes pending replication jobs reliably across multiple application nodes,
So that backup replication completes asynchronously without distributed locking and permanently failed jobs are quarantined for manual review.

**Acceptance Criteria:**

**Given** one or more `outbox_replication_jobs` records with status `PENDING`
**When** `OutboxPollerScheduler` fires on its `app.storage.poller.fixed-delay-ms` schedule
**Then** it fetches up to `app.storage.poller.batch-size` PENDING jobs using `SELECT ... FOR UPDATE SKIP LOCKED ORDER BY created_at ASC`
**And** for each job: transitions status to `PROCESSING`, dispatches based on `job_type`:
- `job_type = 'REPLICATE'`: calls `backupStorageService.put(key, ...)` streaming from the primary provider
- `job_type = 'DELETE'`: calls `backupStorageService.delete(key)` to remove the object from the backup provider
**And** on success, transitions to `COMPLETED`

**Given** a replication call fails transiently
**When** the job is retried
**Then** `attempt_count` is incremented and `last_attempted_at` is updated on each attempt
**And** retry uses exponential backoff with `app.storage.replication.backoff-initial-ms` and `backoff-multiplier`

**Given** a job reaches `attempt_count >= app.storage.replication.max-attempts`
**When** the next attempt also fails
**Then** the job transitions to `FAILED` (dead-letter state) and is never automatically retried
**And** `error_message` is populated with the last exception message for manual diagnosis

**Given** two application nodes are running simultaneously
**When** both nodes' pollers fire at the same time
**Then** `SKIP LOCKED` ensures each job is processed by exactly one node — no duplicate replication occurs

**Given** `OutboxReplicationJobRepositoryIT` (integration test extending `BaseStorageIT`)
**When** it runs against a real PostgreSQL container
**Then** it verifies: `pollPending` with SKIP LOCKED returns disjoint sets when called from two concurrent transactions; a FAILED job is not returned by `pollPending`; a COMPLETED job is not returned by `pollPending`; a job with `job_type = 'DELETE'` is dispatched to `backupStorageService.delete` (not `.put`)

**Given** `app.storage.replication.enabled=false`
**When** the poller fires
**Then** it exits immediately without processing any jobs (guarded by the config flag)

---

## Epic 5: Local Provider, Observability & Production Readiness

Developers can run and test locally with production-parity, CI validates the full system against MinIO, and operations can monitor storage via metrics and tracing.

### Story 5.1: Local File System Storage Provider

As a developer working locally or writing unit tests,
I want a `LocalFileSystemStorageService` that mirrors the S3 bucket directory structure,
So that I can develop and test storage logic without needing a running S3 or MinIO instance.

**Acceptance Criteria:**

**Given** `app.storage.provider=local` is configured with a base directory
**When** `StorageConfig` creates the `StorageService` bean
**Then** `LocalFileSystemStorageService` is wired as the active implementation

**Given** `LocalFileSystemStorageService.put(key, data, contentLength, contentType)` is called with key `documents/42/2026/05/uuid.pdf`
**When** the operation executes
**Then** the file is written to `{baseDir}/documents/42/2026/05/uuid.pdf` (mirroring the S3 key as a relative path)
**And** intermediate directories are created automatically
**And** all I/O uses streaming — no `readAllBytes()` or byte array buffering

**Given** `LocalFileSystemStorageService` is used for `get`, `delete`, `exists`, `stat`, and `copy`
**When** each method executes
**Then** it behaves consistently with `S3StorageService` semantics (e.g., `get` on a non-existent key throws `StorageObjectNotFoundException`)

**Given** `LocalFileSystemStorageServiceTest` (pure unit test, no containers)
**When** it runs
**Then** it covers: put creates the correct directory structure; get returns the correct stream; delete removes the file; exists returns true/false correctly; stat returns correct metadata; copy duplicates the file to a new key

### Story 5.2: Full Integration Test Suite

As a developer and CI pipeline,
I want complete integration test coverage for all storage components using the `BaseStorageIT` harness established in Story 1.3,
So that every storage endpoint, service, and repository is verified against a real MinIO and PostgreSQL instance in CI.

**Acceptance Criteria:**

**Given** `StorageResourceIT` extends `BaseStorageIT`
**When** it runs
**Then** it covers the full upload flow (sign → PUT to MinIO → confirm), the download flow (sign-download → GET from MinIO), the delete flow (DELETE → 204 → download returns 404), and quota rejection when the tenant limit is exceeded

**Given** `S3StorageServiceIT` extends `BaseStorageIT`
**When** it runs
**Then** it verifies put/get/delete/exists/stat/copy against a real MinIO instance using `Instancio`-generated test data and `AssertJ` assertions

**Given** `StorageSigningServiceIT` extends `BaseStorageIT`
**When** it runs
**Then** it verifies quota enforcement: a second upload is rejected when `SUM(size_bytes)` would exceed the configured limit

**Given** `OutboxReplicationJobRepositoryIT` extends `BaseStorageIT`
**When** it runs
**Then** it verifies SKIP LOCKED behaviour: two concurrent transactions calling `pollPending` receive disjoint job sets with no overlap

**Given** all integration tests in the suite
**When** they run in CI
**Then** they pass against a freshly started MinIO + PostgreSQL container pair with no pre-existing state

### Story 5.3: Observability Instrumentation

As a system operator,
I want named Micrometer metrics, structured logging with request context, and confirmed `@Observed` tracing across all storage operations,
So that I can monitor storage health, diagnose latency spikes, and trace requests end-to-end in production.

**Acceptance Criteria:**

**Given** a file upload, download, deletion, or replication event occurs
**When** it completes (success or failure)
**Then** the following Micrometer metrics are recorded using named constants defined in a `StorageMetrics` class:
- `storage.upload.latency` (timer, tags: `provider`, `status`)
- `storage.download.latency` (timer, tags: `provider`, `status`)
- `storage.delete.latency` (timer, tags: `provider`, `status`)
- `storage.replication.queue.depth` (gauge: count of PENDING `outbox_replication_jobs`)
- `storage.file.size.bytes` (distribution summary, recorded at confirm-upload time)
- `storage.error.count` (counter, tags: `operation`, `error_code`)

**Given** any `StorageService` method executes
**When** an exception occurs
**Then** `@Slf4j` structured logging includes MDC context fields: `storageKey`, `tenantId`, `operation`, `provider` — never raw file content or credentials

**Given** `app.storage.replication.queue.depth` gauge
**When** `OutboxPollerScheduler` runs
**Then** the gauge value reflects the current count of PENDING jobs in `outbox_replication_jobs` (queried at each poll cycle)

### Story 5.4: Provider Portability Utilities

As a system operator performing a provider migration,
I want a streaming copy utility and metadata export/import capability,
So that I can migrate all stored objects from one S3-compatible provider to another without loading files into memory or losing metadata.

**Acceptance Criteria:**

**Given** a source `StorageService` (Provider A) and a destination `StorageService` (Provider B)
**When** `StorageMigrationService.migrate(sourceKey, destinationKey)` is called
**Then** the object is streamed directly from Provider A to Provider B using `source.get(key)` piped to `destination.put(key, ...)`
**And** `InputStream.readAllBytes()` is never called — migration is fully streaming

**Given** `StorageMigrationService.migrateAll(tenantId)` is called
**When** it executes
**Then** it iterates all non-deleted `file_storage_objects` for the tenant and calls `migrate` for each, logging progress and skipping already-migrated keys
**And** failures on individual objects are logged with the key and error but do not abort the batch

**Given** `StorageMigrationService.exportMetadata(tenantId)` is called
**When** it executes
**Then** it returns a serializable list of `StorageObjectDto` records for all non-deleted objects owned by the tenant (suitable for import into a new system)

**Given** `StorageMigrationService.importMetadata(List<StorageObjectDto>)` is called
**When** it executes
**Then** it inserts `file_storage_objects` records for each DTO, skipping any key that already exists (idempotent)

**Given** an integration test for `StorageMigrationService`
**When** it runs against two MinIO containers (source and destination)
**Then** `migrate` streams a file from source MinIO to destination MinIO and the file is readable from the destination
**And** `exportMetadata` returns all confirmed objects; `importMetadata` re-inserts them idempotently

### Review Findings

- [x] [Review][Decision] Missing Timeout Configuration (Patched) — requestTimeoutMs and connectionTimeoutMs properties missing from explicit config exposure.
- [x] [Review][Patch] Secrets Management (Patched) — Hardcoded credentials in StorageProperties should use environment variables or secret management.
- [x] [Review][Patch] Filename Sanitization (Patched) — Verify sanitization for null bytes and path traversal.
- [x] [Review][Defer] Outbox Job Types — deferred, pre-existing
- [x] [Review][Defer] Custom Tags Support — deferred, pre-existing
