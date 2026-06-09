---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-05-25'
inputDocuments:
  - '_bmad-output/planning-artifacts/file-storage-module/prd.md'
  - '_bmad-output/planning-artifacts/file-storage-module/addendum.md'
  - '_bmad-output/project-context.md'
workflowType: 'architecture'
project_name: 'javatemplate'
user_name: 'Mbah'
date: '2026-05-25'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
6 categories (FR-01 – FR-06), ~20 sub-requirements covering: a provider-agnostic
`StorageService` interface (Put/Get/Delete/Exists/Stat/Copy); async backup replication
via a durable database outbox; pre-signed URL generation with configurable TTL; a
centralized `StorageKeyGenerator` enforcing `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}`;
pluggable pre-storage validation (MIME, extension, size, checksum, optional antivirus/image
hooks); extended metadata (filename, content-type, checksum, tags, tenant); multipart upload
for large files; and a full observability pipeline.

**Non-Functional Requirements:**
- Reliability: exponential backoff + retries on all network-bound operations
- Performance: streaming via InputStream/OutputStream — no memory-loading of file content
- Portability: full decoupling from provider-specific APIs; provider migration utility (stream-copy A → B)
- Scalability: horizontally scalable replication workers

**Scale & Complexity:**

- Primary domain: Backend infrastructure module (no frontend UI surface)
- Complexity level: **High** — async replication, outbox pattern, streaming, pluggable validation chain, multi-provider switching
- Estimated architectural components: StorageService interface + 3 implementations, OutboxPoller/Scheduler, ValidationChain, StorageKeyGenerator, PreSignedUrlService, Flyway migrations, Metrics/Tracing instrumentation, MinIO integration test harness

### Technical Constraints & Dependencies

- Must fit the Modular Monolith + DDD structure: lives under `com.softropic.skillars.infrastructure` (business-agnostic) or as a reusable `platform` module
- All schema changes via Flyway only; no DDL in Java code
- All REST endpoints require `@PreAuthorize`; all resources annotated `@Observed`
- Spring Boot 3.5 / Java 17; AWS SDK v2 (or SDK-agnostic S3 client); Testcontainers + MinIO for CI
- Secrets injected via env vars or secret manager — never hardcoded
- Bucket versioning disabled; soft-deletes are caller responsibility (not this module)
- Module provides metadata during transport; persistence/querying is the consuming app's responsibility

### Cross-Cutting Concerns Identified

- **Security:** Pre-storage validation pipeline, path sanitization, pre-signed URL TTL enforcement, TLS/SSE configuration, `@PreAuthorize` on all endpoints
- **Observability:** Structured logging, Micrometer metrics (latency, error rate, outbox queue depth, file size distribution), OTel tracing via `@Observed`
- **Transactionality:** Safe sequencing pattern (upload → DB ref → commit) must be enforced across all write paths
- **Configuration:** All provider settings, timeouts, TTLs, replication toggle, bucket names externalized via YAML with consistent key schema
- **Resilience:** Retry with exponential backoff; outbox dead-letter strategy for permanently failed replication jobs

## Starter Template Evaluation

### Primary Technology Domain

Backend infrastructure module added to an existing Spring Boot 3.5 / Java 17 modular monolith. No project scaffolding CLI is applicable — the module is initialized by following the established DDD layer convention.

### Starter Options Considered

Since the project already exists with a defined architecture and toolchain, no external starter template is evaluated. The "starter" is the project's own module scaffolding convention.

### Selected Starter: Existing Module Convention (DDD Layer Template)

**Rationale for Selection:**
The project uses a Modular Monolith + DDD structure. Any new module must follow the same package hierarchy and layer conventions as existing modules (`security`, `tenant`, `notification`, etc.) to ensure architectural consistency and enable AI agents to implement without ambiguity.

**Initialization:** Create the following package structure under `com.softropic.skillars.infrastructure.storage` (business-agnostic placement):

```
infrastructure/storage/
  api/            ← REST Resources (@RestController, @Observed, @PreAuthorize)
  service/        ← Business logic, StorageService implementations
  repo/           ← JPA Entities, Spring Data Repositories
  contract/       ← Public API: DTO records, Events, Exceptions
  config/         ← @Configuration beans (storage provider wiring)
  infrastructure/ ← (optional) Storage-specific technical logic
```

**Architectural Decisions Established by Convention:**

- **Language & Runtime:** Java 17 with records for all DTOs
- **Build Tooling:** Maven (existing project build system)
- **Persistence:** Flyway migrations for all schema changes; Hibernate 6 entities with Lombok
- **Mapping:** MapStruct for all Entity ↔ DTO conversions; mappers in `contract` or `service`
- **Testing:** @SpringBootTest + @Testcontainers + MinIO container; Instancio for test data; AssertJ assertions
- **Observability:** @Observed on all resource methods; structured logging via @Slf4j
- **Security:** @PreAuthorize on every endpoint using SecurityConstants
- **Code Organization:** Dependency flow — `platform` depends on `infrastructure`; `infrastructure` must not depend on `platform`

**Note:** Module initialization (package creation, base config class, first Flyway migration) should be the first implementation story.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- Module DB ownership model (`file_storage_objects` + `outbox_replication_jobs`)
- S3 client library (AWS SDK v2)
- Outbox polling mechanism (`@Scheduled` + `SKIP LOCKED`)
- API surface (pre-signed URL gateway with quota enforcement)
- Retry mechanism (Spring Retry)

**Important Decisions (Shape Architecture):**
- Upload confirmation flow (client confirms after direct-to-S3 upload)
- Quota enforcement at sign-time using `file_storage_objects` aggregates
- Download egress tracking via signed URL issuance events (estimated, not exact bytes)

**Deferred Decisions (Post-MVP):**
- Exact-byte egress tracking (requires CloudWatch/MinIO metrics integration or download proxy)
- Antivirus and image verification hook implementations
- Provider migration utility (stream-copy A → B)

---

### Data Architecture

**Decision: Module DB Ownership**
- The module owns two tables in the application database:
  - `file_storage_objects` — canonical record of every stored object (`key`, `tenant_id`, `original_filename`, `content_type`, `size_bytes`, `checksum`, `custom_tags`, `provider`, `bucket`, `upload_confirmed_at`, `deleted_at`)
  - `outbox_replication_jobs` — replication state machine (`storage_object_id`, `status` [PENDING/PROCESSING/COMPLETED/FAILED], `attempt_count`, `last_attempted_at`, `error_message`, `created_at`)
- The core `StorageService` remains a pure gateway; the module builds full lifecycle management on top of it using these tables.
- Rationale: Enables quota enforcement, access auditing, and replication tracking within the module without coupling to consuming apps.

**Decision: Outbox Polling Mechanism**
- `@Scheduled` method polls for PENDING jobs using `SELECT ... FOR UPDATE SKIP LOCKED` to safely support multiple application nodes without distributed locking overhead.
- Retry count and backoff intervals are configurable via YAML.
- Jobs exceeding `max_attempts` transition to FAILED (dead-letter state); no automatic purge — manual intervention or a separate cleanup job required.
- Rationale: Spring-native, zero extra infrastructure, safe for horizontal scaling.

**Decision: Quota Enforcement**
- Before issuing any signed upload URL, the module computes:
  `SELECT SUM(size_bytes) FROM file_storage_objects WHERE tenant_id = ?`
  and rejects if `(current_usage + requested_size) > configured_quota`.
- Quota limits are configurable per tenant via YAML or a future quota table.
- Storage usage is updated when the upload confirmation endpoint commits the `file_storage_objects` record (not at sign-time, to avoid phantom quota consumption).

---

### Authentication & Security

**Decision: Retry Mechanism**
- Spring Retry (`@Retryable` + `@Recover`) for all network-bound operations: S3 put/get/delete/head, pre-signed URL generation, replication calls.
- Exponential backoff with configurable initial interval, multiplier, and max attempts — all externalized via YAML.
- Rationale: Spring-native, annotation-driven, zero extra infrastructure.

---

### API & Communication Patterns

**Decision: S3 Client Library**
- AWS SDK v2 (`software.amazon.awssdk:s3`) as the S3 client for all providers.
- Provider endpoint URL is fully externalized via YAML, enabling seamless switching between AWS S3, MinIO, Cloudflare R2, Wasabi, etc.
- Pre-signed URL generation uses the SDK's built-in `S3Presigner`.
- Async client (`S3AsyncClient`) available for streaming/multipart operations.
- Rationale: Industry standard for S3-compatible APIs; MinIO itself recommends it.

**Decision: REST API Surface (Pre-signed URL Gateway)**
- The module exposes a minimal REST surface:
  - `POST /storage/sign/upload` — validate quota, generate pre-signed PUT URL, return key + URL + TTL
  - `POST /storage/confirm/{key}` — verify upload exists in S3, create `file_storage_objects` record, trigger replication outbox entry
  - `GET /storage/sign/download/{key}` — verify object exists, record access event, generate pre-signed GET URL
  - `DELETE /storage/{key}` — soft-delete flow (mark `deleted_at`), schedule physical deletion and backup deletion via outbox
- No byte proxying through the backend; all file data flows directly between client and S3.
- Egress monitoring: every signed download URL issuance is recorded as a `StorageAccessEvent` (`key`, `tenant`, `size_bytes`, `timestamp`) for usage estimation.
- True byte-level egress requires S3-side metrics (CloudWatch/MinIO) — deferred.

### Decision Impact Analysis

**Implementation Sequence:**
1. Flyway migrations: `file_storage_objects` + `outbox_replication_jobs` tables
2. `StorageService` interface + `S3StorageService` implementation (AWS SDK v2)
3. `StorageKeyGenerator` (key naming convention)
4. `ValidationChain` (MIME, extension, size, checksum)
5. Sign-upload endpoint + quota enforcement
6. Upload confirmation endpoint + `file_storage_objects` record creation
7. Sign-download endpoint + access event recording
8. Outbox poller (`@Scheduled` + `SKIP LOCKED`) + replication logic
9. Delete flow (soft-delete + outbox entries)
10. `LocalFileSystemStorageService` + `ReplicatedStorageService`
11. Observability instrumentation (metrics, tracing, structured logs)
12. MinIO integration test harness

**Cross-Component Dependencies:**
- Quota enforcement depends on `file_storage_objects` being populated by confirm-upload
- Replication outbox depends on `file_storage_objects` (foreign key: `storage_object_id`)
- All REST endpoints depend on `@PreAuthorize` + `SecurityConstants` (project rule)
- All S3 operations must be wrapped with `@Retryable` (Spring Retry)
- All resource methods must be annotated with `@Observed` (project rule)

## Implementation Patterns & Consistency Rules

### Critical Conflict Points Identified

8 areas where AI agents could diverge without explicit rules: REST path prefix, config key namespace, access event persistence, utility class placement, validation timing, API response shape, error envelope format, and transaction boundary.

---

### Naming Patterns

**REST Endpoint Naming:**
All storage endpoints are prefixed `/api/storage/`:
- `POST   /api/storage/sign/upload`
- `POST   /api/storage/confirm/{key}`
- `GET    /api/storage/sign/download/{key}`
- `DELETE /api/storage/{key}`

Path parameters use `{camelCase}`; no `/api/files/` or `/api/store/` variants.

**YAML Configuration Namespace:**
All storage properties are nested under `app.storage.*`:
```yaml
app:
  storage:
    provider: s3          # s3 | local
    bucket: my-app-dev
    endpoint-url: https://... # override for MinIO / non-AWS
    presign-ttl-seconds: 300
    upload-ttl-seconds: 600
    replication:
      enabled: true
      max-attempts: 5
      backoff-initial-ms: 1000
      backoff-multiplier: 2.0
    quota:
      default-bytes: 10737418240   # 10 GB
    poller:
      fixed-delay-ms: 5000
      batch-size: 10
```

**Database Table Names (snake_case, plural):**
- `file_storage_objects` — canonical object registry
- `outbox_replication_jobs` — async replication state machine
- `storage_access_events` — egress tracking per download URL issuance

**Java Class Names:**
- `StorageResource` — REST controller (`api/`)
- `StorageService` — interface (`service/`)
- `S3StorageService`, `LocalFileSystemStorageService`, `ReplicatedStorageService` — implementations (`service/`)
- `StorageKeyGenerator` — key naming utility (`service/`)
- `ValidationChain` — pluggable validator chain (`service/`)
- `StorageConfig` — Spring `@Configuration` (`config/`)
- `StorageProperties` — `@ConfigurationProperties(prefix = "app.storage")` (`config/`)
- `StorageErrorCode` — module `ErrorCode` enum (`contract/`)

**ErrorCode Enum:**
Define a single `StorageErrorCode` enum in `contract/` implementing
`com.softropic.skillars.infrastructure.exception.ErrorCode`:
```java
public enum StorageErrorCode implements ErrorCode {
    STORAGE_OBJECT_NOT_FOUND,
    QUOTA_EXCEEDED,
    UPLOAD_NOT_CONFIRMED,
    VALIDATION_FAILED,
    REPLICATION_FAILED,
    PROVIDER_ERROR;

    @Override public String getErrorCode() { return this.name(); }
}
```

---

### Structure Patterns

**Package Layout:**
```
com.softropic.skillars.infrastructure.storage/
  api/           StorageResource.java
  service/       StorageService.java, S3StorageService.java,
                 LocalFileSystemStorageService.java, ReplicatedStorageService.java,
                 StorageKeyGenerator.java, ValidationChain.java, ValidationStep.java
  repo/          FileStorageObject.java, OutboxReplicationJob.java,
                 StorageAccessEvent.java (entities)
                 FileStorageObjectRepository.java, OutboxReplicationJobRepository.java,
                 StorageAccessEventRepository.java
  contract/      SignUploadRequest.java, SignUploadResponse.java,
                 ConfirmUploadResponse.java, SignDownloadResponse.java,
                 StorageObjectDto.java, StorageErrorCode.java,
                 StorageObjectNotFoundException.java, QuotaExceededException.java,
                 StorageValidationException.java, StorageProviderException.java
  config/        StorageConfig.java, StorageProperties.java
```

**Test Structure:**
- MinIO `@Testcontainers` configuration lives in a shared `BaseStorageIT` base class
- Unit tests use `LocalFileSystemStorageService` (no container needed)
- Integration tests extend `BaseStorageIT` which spins up MinIO

---

### Format Patterns

**API Response Bodies (all dates as ISO 8601 strings):**
```
POST /api/storage/sign/upload → 200
{ "key": "documents/42/2026/05/uuid.pdf",
  "uploadUrl": "https://...", "expiresAt": "2026-05-25T10:00:00Z" }

POST /api/storage/confirm/{key} → 200
{ "id": "uuid", "key": "...", "sizeBytes": 12345,
  "contentType": "application/pdf", "checksum": "sha256:...",
  "uploadedAt": "2026-05-25T09:59:00Z" }

GET /api/storage/sign/download/{key} → 200
{ "key": "...", "downloadUrl": "https://...", "expiresAt": "2026-05-25T10:05:00Z" }

DELETE /api/storage/{key} → 204 (no body)
```

**Error Response Envelope:**
All error responses use the existing `ErrorDto` shape — never `ProblemDetail`:
```json
{ "helpCode": "XkP9m",
  "errorMsg": { "key": "storage.quotaExceeded",
                "message": "Upload would exceed the 10 GB storage quota." },
  "fieldErrors": [] }
```
Storage-specific i18n message keys use the `storage.*` namespace
(e.g., `storage.quotaExceeded`, `storage.validationFailed`, `storage.objectNotFound`).

---

### Process Patterns

**Exception Pattern:**
All storage exceptions extend `ApplicationException` and carry a `StorageErrorCode`:
```java
// In contract/
public class QuotaExceededException extends ApplicationException {
    public QuotaExceededException(String msg, Map<String, Object> logContext) {
        super(msg, logContext, StorageErrorCode.QUOTA_EXCEEDED);
    }
}
```
Register a dedicated `@ExceptionHandler` in `ApiAdvice` for each new storage exception type:
```java
@ExceptionHandler(QuotaExceededException.class)
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public ErrorDto storageQuotaHandler(QuotaExceededException ex) { ... }
```

**Transaction Boundary — Confirm-Upload (CRITICAL):**
The `@Transactional` boundary wraps ONLY the DB writes, never the S3 call.
Correct sequence:
```
1. s3Client.headObject(key)          ← outside @Transactional
2. validate(headObjectResponse)      ← outside @Transactional
3. @Transactional begins
4.   insert file_storage_objects
5.   insert outbox_replication_jobs
6. @Transactional commits
```
Wrapping the S3 call inside `@Transactional` holds a DB connection open during
network I/O — this is a hard anti-pattern for this module.

**Dual Validation:**
- At sign-time: validate declared `contentType`, `extension`, `fileSizeBytes` against configured allowlists/limits. Reject before issuing URL.
- At confirm-time: call S3 `HeadObject`, compare actual `ContentType`, `ContentLength`, `ETag` against sign-time declaration. Reject and soft-delete orphan if mismatch.

**Retry Pattern:**
All `StorageService` methods that call S3 are annotated with `@Retryable`:
```java
@Retryable(retryFor = StorageProviderException.class,
           maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
           backoff = @Backoff(delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
                             multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"))
```
`@Recover` methods log the failure and rethrow as `StorageProviderException`.

**Outbox Poller — SKIP LOCKED:**
```java
@Query("SELECT j FROM OutboxReplicationJob j WHERE j.status = 'PENDING'
        ORDER BY j.createdAt ASC LIMIT :limit FOR UPDATE SKIP LOCKED")
List<OutboxReplicationJob> pollPending(@Param("limit") int limit);
```
The poller acquires row locks before processing; other nodes skip locked rows automatically. Failed jobs (attempts >= max) transition to FAILED, never retried automatically.

---

### Enforcement Guidelines

**All AI Agents MUST:**
- Never wrap S3 client calls inside a `@Transactional` method
- Always extend `ApplicationException` with a `StorageErrorCode` — never throw raw `RuntimeException`
- Always register a new exception type in `ApiAdvice` before it can surface to clients
- Always annotate `StorageResource` methods with both `@PreAuthorize` and `@Observed`
- Always externalize every timeout, TTL, retry count, and bucket name to `app.storage.*`
- Always use `SKIP LOCKED` when polling `outbox_replication_jobs` for multi-node safety

**Anti-Patterns:**
- Wrapping S3 calls inside `@Transactional` (holds DB connection during network I/O)
- Using `ProblemDetail` or custom maps as error responses instead of `ErrorDto`
- Hardcoding bucket names, endpoint URLs, or TTL values in Java code
- Accessing `file_storage_objects` from outside the `infrastructure.storage` module
- Performing quota enforcement after issuing the signed URL (phantom quota window)
- Throwing generic exceptions without a `StorageErrorCode`

## Project Structure & Boundaries

### Requirements to Structure Mapping

| FR Category | Implementation Location |
|---|---|
| FR-01 Unified Storage Gateway | `infrastructure/storage/service/` — `StorageService` interface + 3 implementations |
| FR-02 Resilient Data Management | `infrastructure/storage/repo/` (entities) + `service/ReplicationOutboxService` + `service/OutboxPollerScheduler` |
| FR-03 Signed URL & Naming | `infrastructure/storage/api/StorageResource` + `service/StorageKeyGenerator` + `service/StorageSigningService` |
| FR-04 Content Validation & Security | `infrastructure/storage/service/ValidationChain` + individual `ValidationStep` impls |
| FR-05 Metadata & Large Files | `infrastructure/storage/repo/FileStorageObject` + multipart logic in `S3StorageService` |
| FR-06 Observability & Testing | `@Observed` on resource, `BaseStorageIT`, `MinioContainerConfig`, metrics in service layer |

---

### Complete Project Directory Structure

```
src/
├── main/
│   ├── java/com/softropic/skillars/
│   │   └── infrastructure/
│   │       └── storage/
│   │           ├── api/
│   │           │   └── StorageResource.java
│   │           ├── config/
│   │           │   ├── StorageConfig.java              ← S3Client, S3Presigner, StorageService @Bean wiring
│   │           │   └── StorageProperties.java          ← @ConfigurationProperties("app.storage")
│   │           ├── contract/
│   │           │   ├── SignUploadRequest.java           ← record (contentType, extension, fileSizeBytes, tenantId)
│   │           │   ├── SignUploadResponse.java          ← record (key, uploadUrl, expiresAt)
│   │           │   ├── ConfirmUploadResponse.java       ← record (id, key, sizeBytes, contentType, checksum, uploadedAt)
│   │           │   ├── SignDownloadResponse.java        ← record (key, downloadUrl, expiresAt)
│   │           │   ├── StorageObjectDto.java            ← record (full object representation)
│   │           │   ├── StorageErrorCode.java            ← enum implements ErrorCode
│   │           │   ├── exception/
│   │           │   │   ├── StorageObjectNotFoundException.java
│   │           │   │   ├── QuotaExceededException.java
│   │           │   │   ├── StorageValidationException.java
│   │           │   │   └── StorageProviderException.java
│   │           │   └── event/
│   │           │       └── StorageObjectConfirmedEvent.java  ← Spring ApplicationEvent for consumers
│   │           ├── repo/
│   │           │   ├── FileStorageObject.java           ← @Entity, @Getter, @Slf4j
│   │           │   ├── FileStorageObjectRepository.java ← JpaRepository + quota query
│   │           │   ├── OutboxReplicationJob.java        ← @Entity
│   │           │   ├── OutboxReplicationJobRepository.java ← SKIP LOCKED query
│   │           │   ├── StorageAccessEvent.java          ← @Entity
│   │           │   └── StorageAccessEventRepository.java
│   │           └── service/
│   │               ├── StorageService.java              ← interface (put, get, delete, exists, stat, copy)
│   │               ├── S3StorageService.java            ← AWS SDK v2, @Retryable on all S3 calls
│   │               ├── LocalFileSystemStorageService.java ← mirrors bucket directory structure
│   │               ├── ReplicatedStorageService.java    ← delegates to primary + triggers outbox
│   │               ├── StorageSigningService.java       ← quota check + S3Presigner + access event
│   │               ├── StorageKeyGenerator.java         ← {entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}
│   │               ├── ValidationChain.java             ← ordered List<ValidationStep>
│   │               ├── ValidationStep.java              ← interface
│   │               └── validation/
│   │                   ├── MimeTypeValidationStep.java
│   │                   ├── ExtensionValidationStep.java
│   │                   ├── FileSizeValidationStep.java
│   │                   └── ChecksumValidationStep.java
│   └── resources/
│       ├── db/migration/
│       │   ├── V12__storage_schema.sql                 ← file_storage_objects, outbox_replication_jobs
│       │   └── V13__storage_access_events.sql          ← storage_access_events
│       └── i18n/
│           ├── messages.properties                      ← add storage.* keys
│           ├── messages_en.properties                   ← add storage.* keys
│           └── messages_fr.properties                   ← add storage.* keys
│
└── test/
    └── java/com/softropic/skillars/
        ├── config/
        │   └── MinioContainerConfig.java               ← @TestConfiguration, mirrors PostgresContainerConfig
        └── infrastructure/
            └── storage/
                ├── BaseStorageIT.java                  ← @SpringBootTest + MinioContainerConfig + PostgresContainerConfig
                ├── api/
                │   └── StorageResourceIT.java          ← endpoint integration tests
                ├── service/
                │   ├── S3StorageServiceIT.java         ← extends BaseStorageIT (real MinIO)
                │   ├── StorageSigningServiceIT.java    ← quota enforcement tests
                │   ├── LocalFileSystemStorageServiceTest.java ← pure unit test
                │   └── ValidationChainTest.java        ← pure unit test (no containers)
                └── repo/
                    └── OutboxReplicationJobRepositoryIT.java ← SKIP LOCKED behaviour test
```

---

### Architectural Boundaries

**API Boundary — What enters and exits the module:**
- Enters: `SignUploadRequest` (record DTO), storage key (String), tenant context via `SecurityContextHolder`
- Exits: `SignUploadResponse`, `ConfirmUploadResponse`, `SignDownloadResponse`, `ErrorDto` on failure
- Never exits: JPA entities (`FileStorageObject`, `OutboxReplicationJob`), raw S3 SDK types

**Service Boundary — Internal module contract:**
- `StorageService` interface is the only entry point for storage operations from within other services
- `StorageSigningService` is the only component that issues pre-signed URLs and enforces quotas
- `ReplicationOutboxService` is the only component that writes to `outbox_replication_jobs`
- No other module may query `file_storage_objects`, `outbox_replication_jobs`, or `storage_access_events` directly

**Data Boundary — DB table ownership:**
- `file_storage_objects`, `outbox_replication_jobs`, `storage_access_events` are exclusively owned by this module
- Consuming applications reference file objects by their storage `key` (String) — never by foreign key into this module's tables
- Cross-module notification of confirmed uploads is via `StorageObjectConfirmedEvent` (Spring `ApplicationEvent`)

---

### Integration Points

**Internal Communication:**
- `StorageResource` → `StorageSigningService` → `StorageService` (sign-upload flow)
- `StorageResource` → `StorageSigningService` → S3 HeadObject → `StorageSigningService` → DB write (confirm-upload flow)
- `OutboxPollerScheduler` → `OutboxReplicationJobRepository` (SKIP LOCKED) → `ReplicatedStorageService`
- Confirmed upload → `ApplicationEventPublisher.publishEvent(StorageObjectConfirmedEvent)` for any consumer

**External Integration (S3-compatible provider):**
- All S3 calls go through `S3StorageService` using AWS SDK v2
- Endpoint URL, credentials, and bucket name injected from `app.storage.*` YAML — never hardcoded
- `S3Presigner` bean created in `StorageConfig`, scoped to the configured provider

**Data Flow — Upload:**
```
Client → POST /api/storage/sign/upload
  → StorageResource → StorageSigningService
  → ValidationChain (declared metadata)
  → quota check (SUM file_storage_objects)
  → S3Presigner.presignPutObject()
  → return {key, uploadUrl, expiresAt}
Client → PUT {uploadUrl} directly to S3
Client → POST /api/storage/confirm/{key}
  → StorageSigningService → s3.headObject(key) [outside @Transactional]
  → re-validate actuals vs declared
  → @Transactional: insert file_storage_objects + insert outbox_replication_jobs
  → publishEvent(StorageObjectConfirmedEvent)
  → return ConfirmUploadResponse
```

**Data Flow — Download:**
```
Client → GET /api/storage/sign/download/{key}
  → StorageResource → StorageSigningService
  → verify file_storage_objects record exists
  → insert storage_access_events (egress tracking)
  → S3Presigner.presignGetObject()
  → return {key, downloadUrl, expiresAt}
Client → GET {downloadUrl} directly from S3
```

---

### File Organization Patterns

**Configuration Files:**
- `StorageProperties` maps all `app.storage.*` keys; no storage config lives in `application.yml` of other modules
- Per-environment overrides via standard Spring profiles (`application-dev.yml`, `application-prod.yml`)

**Flyway Migrations:**
- `V12__storage_schema.sql` — creates `file_storage_objects` and `outbox_replication_jobs` with indexes and FK constraints
- `V13__storage_access_events.sql` — creates `storage_access_events` table
- All constraints follow snake_case naming: `fk_replication_job_storage_object`, `idx_storage_objects_tenant_id`, etc.

**i18n Keys (storage.* namespace):**
```properties
storage.objectNotFound=The requested file could not be found.
storage.quotaExceeded=Upload would exceed your storage quota.
storage.validationFailed=File validation failed: {0}
storage.uploadNotConfirmed=Upload was not confirmed within the allowed window.
storage.providerError=A storage provider error occurred. Please retry or contact support.
```

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
All technology decisions are mutually compatible: Java 17 + Spring Boot 3.5.11 + AWS SDK v2 + Spring Retry + Testcontainers + MapStruct + Lombok + PostgreSQL. `@Retryable` and `@Transactional` are intentionally on separate methods (S3 calls and DB writes respectively), eliminating any retry-transaction interaction risk. `SKIP LOCKED` is PostgreSQL-native and matches the project's exclusive use of PostgreSQL.

**Pattern Consistency:**
Naming conventions, error envelope (`ErrorDto`), test conventions (IT/Test suffix), Flyway versioning (V12+), and i18n key namespace (`storage.*`) are internally consistent with the existing codebase patterns.

**Structure Alignment:**
`infrastructure.storage` placement respects the business-agnostic constraint. Dependency flow (platform → infrastructure, never reversed) is enforced by the boundary rule that no other module may query storage tables directly. Consumers reference files by storage key (String) only.

---

### Requirements Coverage Validation

| Requirement | Status | Notes |
|---|---|---|
| FR-01.01 StorageService interface | ✅ | Put/Get/Delete/Exists/Stat/Copy defined |
| FR-01.02 Three implementations | ✅ | S3, Local, Replicated |
| FR-01.03 YAML-externalized config | ✅ | `app.storage.*` fully defined |
| FR-02.01 Async backup | ✅ | Outbox poller |
| FR-02.02 Durable outbox | ✅ | `outbox_replication_jobs` + SKIP LOCKED |
| FR-02.03 Deletion flow | ⚠️ | Physical deletion scheduler added (see gaps) |
| FR-02.04 Replication toggle | ✅ | `app.storage.replication.enabled` |
| FR-03.01 Pre-signed URLs | ✅ | `StorageSigningService` + S3Presigner |
| FR-03.02 Key naming convention | ✅ | `StorageKeyGenerator` |
| FR-03.03 Cache busting | ✅ | UUID in key |
| FR-04.01 Pre-storage validation | ✅ | `ValidationChain` dual-stage |
| FR-04.02 Bucket isolation | ✅ | One bucket per app/env via config |
| FR-04.03 Path/filename sanitization | ⚠️ | Sanitization `ValidationStep` added (see gaps) |
| FR-04.04 TLS + SSE | ✅ | SDK default HTTPS + SSE at provider level |
| FR-04.05 Pluggable validation | ✅ | `ValidationStep` interface is open for extension |
| FR-05.01 Extended metadata | ✅ | `file_storage_objects` columns defined |
| FR-05.02 Multipart upload | ⚠️ | `S3TransferManager` pattern added (see gaps) |
| FR-06.01 Observability | ✅ | `@Observed`, structured logs, Micrometer metrics |
| FR-06.02 Testing | ✅ | MinIO container + LocalFS unit tests |
| NFR Reliability | ✅ | Spring Retry + exponential backoff |
| NFR Performance | ✅ | Streaming via S3TransferManager; no readAllBytes() rule added |
| NFR Portability | ✅ | Endpoint URL externalized |
| NFR Scalability | ✅ | Horizontal-safe via SKIP LOCKED |

---

### Gap Analysis Results

**Important Gaps Resolved:**

**Gap 1 — Physical Deletion Scheduler (FR-02.03):**
Add `DeletionSchedulerService` to `service/`. Uses `@Scheduled` + JPQL query with `SKIP LOCKED` on `file_storage_objects WHERE deleted_at < NOW() - retention_days AND physical_deleted_at IS NULL`. Executes: S3 primary delete → outbox entry for backup delete → sets `physical_deleted_at`. Adds column `physical_deleted_at` to `file_storage_objects` and `app.storage.deletion.retention-days: 30` to config.

**Gap 2 — Filename Sanitization (FR-04.03):**
Add `FilenameSanitizationStep` to `service/validation/` as the first step in `ValidationChain`. Strips null bytes, control characters, path traversal sequences (`../`, `./`), normalizes Unicode (NFC), and truncates to 255 chars. The sanitized value replaces `original_filename` in the `SignUploadRequest` before downstream steps.

**Gap 3 — Multipart Upload Pattern (FR-05.02):**
Use `software.amazon.awssdk:s3-transfer-manager` in `S3StorageService`. `TransferManager.upload()` automatically selects multipart vs single-part based on `app.storage.multipart-threshold-bytes: 8388608` (8 MB). Agents must use `TransferManager` — direct `s3Client.putObject()` is reserved for small internal ops only.

**Nice-to-Have Gaps (deferred):**
- Micrometer metric names (define in a follow-up pattern spec)
- `StorageObjectConfirmedEvent` payload structure (define when first consumer is written)
- `custom_tags` schema (default to JSONB column `custom_tags jsonb`)

**Streaming Anti-Pattern Rule (added to enforcement):**
Never call `InputStream.readAllBytes()` or load file content into a `byte[]` in any storage service method. All file I/O must use streaming (`InputStream`→`OutputStream` or `TransferManager`).

---

### Architecture Completeness Checklist

**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

---

### Architecture Readiness Assessment

**Overall Status:** READY FOR IMPLEMENTATION

**Confidence Level:** High — all 16 checklist items confirmed, 3 important gaps resolved during validation, no critical gaps remain.

**Key Strengths:**
- Transaction boundary rule explicitly prevents the most common S3+JPA anti-pattern
- Dual-stage validation (sign-time + confirm-time) closes the gap between declared and actual file metadata
- `SKIP LOCKED` outbox pattern is safe for horizontal scaling without distributed locks
- Provider-agnostic design enforced structurally (no provider types leak past `S3StorageService`)
- Error handling fully integrated with existing `ApplicationException` + `ErrorDto` infrastructure

**Areas for Future Enhancement:**
- Exact-byte egress tracking (requires S3-side CloudWatch/MinIO metrics integration)
- Antivirus and image verification hook implementations (pluggable via `ValidationStep`)
- Micrometer metric name specification
- Provider migration utility (stream-copy A → B)
- Per-tenant quota table (currently YAML-based only)

---

### Implementation Handoff

**AI Agent Guidelines:**
- Read this document fully before writing any code in `infrastructure.storage`
- Follow ALL architectural decisions exactly as documented — no improvisation on naming, structure, or patterns
- The transaction boundary rule (S3 outside `@Transactional`, DB writes inside) is non-negotiable
- Use `S3TransferManager` for all file put operations — never `s3Client.putObject()` directly
- Never call `InputStream.readAllBytes()` on file content in any service method

**First Implementation Priority:**
1. `V12__storage_schema.sql` + `V13__storage_access_events.sql` Flyway migrations
2. `StorageConfig.java` + `StorageProperties.java` wiring
3. `StorageService` interface + `S3StorageService` skeleton with `@Retryable`
