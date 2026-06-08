# Story 1.1: Module Scaffold, Database Schema & Configuration

Status: done

## Story

As a developer integrating the storage module,
I want the storage module's package structure, database schema, configuration model, error codes, and exception hierarchy to be in place,
so that all subsequent storage stories have a consistent, compiling foundation to build on.

## Acceptance Criteria

1. **Package structure exists**: `com.softropic.skillars.infrastructure.storage.{api,service,repo,contract,contract/exception,contract/event,config}` compiles cleanly.

2. **Configuration is bound**: `StorageProperties` is annotated `@ConfigurationProperties(prefix = "app.storage")` and registered via `StorageConfig`. All keys listed in AC-3 are mapped as typed fields.

3. **YAML defaults present**: `application.yaml` contains the following under `app.storage`:
   - `provider` (default: `s3`)
   - `bucket`
   - `endpoint-url`
   - `presign-ttl-seconds` (default: 300)
   - `upload-ttl-seconds` (default: 600)
   - `replication.enabled` (default: false)
   - `quota.default-bytes` (default: 10737418240)
   - `poller.fixed-delay-ms` (default: 5000)
   - `poller.batch-size` (default: 10)
   - `retry.max-attempts` (default: 3)
   - `retry.backoff-initial-ms` (default: 1000)
   - `retry.backoff-multiplier` (default: 2.0)
   - `deletion.retention-days` (default: 30)
   - `s3.request-timeout-ms` (default: 5000)
   - `s3.connection-timeout-ms` (default: 3000)

4. **Flyway V12 runs**: `V12__storage_schema.sql` creates `file_storage_objects` and `outbox_replication_jobs` in the `main` schema with all required columns, indexes, and FK constraints.

5. **Flyway V13 runs**: `V13__storage_access_events.sql` creates `storage_access_events` in the `main` schema.

6. **JPA entities are valid**: `FileStorageObject`, `OutboxReplicationJob`, and `StorageAccessEvent` are `@Entity` classes with Lombok `@Getter`; JPA context loads without errors.

7. **Repositories exist**: `FileStorageObjectRepository`, `OutboxReplicationJobRepository`, and `StorageAccessEventRepository` are Spring Data `JpaRepository` sub-interfaces.

8. **Exception handlers registered**: `ApiAdvice` has `@ExceptionHandler` methods for all 4 storage exceptions, returning correct HTTP status: `StorageObjectNotFoundException` → 404, `QuotaExceededException` → 429, `StorageValidationException` → 422, `StorageProviderException` → 502.

9. **StorageErrorCode enum implements ErrorCode**: Values `STORAGE_OBJECT_NOT_FOUND`, `QUOTA_EXCEEDED`, `UPLOAD_NOT_CONFIRMED`, `VALIDATION_FAILED`, `REPLICATION_FAILED`, `PROVIDER_ERROR`; `getErrorCode()` returns `this.name()`.

10. **i18n keys present** in `messages.properties`, `messages_en.properties`, and `messages_fr.properties`:
    - `storage.objectNotFound`
    - `storage.quotaExceeded`
    - `storage.validationFailed`
    - `storage.uploadNotConfirmed`
    - `storage.providerError`

## Tasks / Subtasks

- [x] Task 1: Create package skeleton (AC: 1)
  - [x] Create `StorageResource.java` shell in `api/` package (empty `@RestController`, `@RequestMapping("/api/storage")`, no methods yet)
  - [x] Create placeholder interfaces/classes to establish all packages: `service/`, `repo/`, `contract/`, `contract/exception/`, `contract/event/`, `config/`

- [x] Task 2: Create `StorageProperties` and `StorageConfig` (AC: 2, 3)
  - [x] Create `StorageProperties.java` in `config/` with `@ConfigurationProperties(prefix = "app.storage")` — all 15 keys as typed fields (use nested static classes or records for `replication`, `quota`, `poller`, `retry`, `deletion`, `s3` sub-groups)
  - [x] Create `StorageConfig.java` in `config/` with `@Configuration` + `@EnableConfigurationProperties(StorageProperties.class)` — no S3 beans yet (those come in Story 1.2)
  - [x] Add `app.storage.*` defaults to `src/main/resources/application.yaml`

- [x] Task 3: Create Flyway migration V12 (AC: 4)
  - [x] Create `src/main/resources/db/migration/V12__storage_schema.sql`
  - [x] `file_storage_objects` table with all required columns
  - [x] `outbox_replication_jobs` table with `job_type` CHECK constraint and FK to `file_storage_objects`
  - [x] Add required indexes

- [x] Task 4: Create Flyway migration V13 (AC: 5)
  - [x] Create `src/main/resources/db/migration/V13__storage_access_events.sql`
  - [x] `storage_access_events` table with required columns

- [x] Task 5: Create JPA entities (AC: 6)
  - [x] Create `FileStorageObject.java` extending `BaseEntity` with all column fields
  - [x] Create `OutboxReplicationJob.java` extending `BaseEntity` with status enum, job_type, attempt tracking
  - [x] Create `StorageAccessEvent.java` extending `BaseEntity` with event columns

- [x] Task 6: Create Spring Data repositories (AC: 7)
  - [x] Create `FileStorageObjectRepository.java`
  - [x] Create `OutboxReplicationJobRepository.java`
  - [x] Create `StorageAccessEventRepository.java`

- [x] Task 7: Create `StorageErrorCode` enum and exception hierarchy (AC: 8, 9)
  - [x] Create `StorageErrorCode.java` in `contract/` implementing `ErrorCode`
  - [x] Create `StorageObjectNotFoundException.java` in `contract/exception/`
  - [x] Create `QuotaExceededException.java` in `contract/exception/`
  - [x] Create `StorageValidationException.java` in `contract/exception/`
  - [x] Create `StorageProviderException.java` in `contract/exception/`

- [x] Task 8: Register exception handlers in `ApiAdvice` (AC: 8)
  - [x] Add 4 `@ExceptionHandler` methods to the existing `ApiAdvice` class

- [x] Task 9: Add i18n keys (AC: 10)
  - [x] Add 5 `storage.*` keys to `messages.properties`
  - [x] Add 5 `storage.*` keys to `messages_en.properties`
  - [x] Add 5 `storage.*` keys to `messages_fr.properties`

### Review Findings

- [x] [Review][Decision] ApiAdvice Handler Inconsistency — i18n keys used (resolved: preferred for frontend resolution)
- [x] [Review][Patch] Redundant IDE Metadata [.idea/compiler.xml]
- [x] [Review][Dismiss] Job Type Mapping Violation [src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJob.java] — user prefers enum over string
- [x] [Review][Patch] Unused Import in ApiAdvice [src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java]
- [x] [Review][Defer] TSID Node Configuration — deferred, pre-existing
- [x] [Review][Defer] Poller Index Suboptimality — deferred, pre-existing

## Dev Notes

### Package Root
All classes live under: `com.softropic.skillars.infrastructure.storage`

This is the **infrastructure** package (not `platform`), making it business-agnostic. No `platform.*` imports are allowed inside this module. The dependency flow is strictly: `platform` → `infrastructure` (never reversed). [Source: architecture.md#Architectural Boundaries]

### ID Strategy — Critical
The project uses **TSID** for all entity IDs, NOT UUID. Entities must extend `BaseEntity` which provides:
```java
@Id @Tsid
@Column(name = "id", updatable = false, nullable = false)
protected Long id;
```
Import: `io.hypersistence.utils.hibernate.id.Tsid`
In Flyway migrations, the `id` column must be `BIGINT` (not UUID). [Source: BaseEntity.java]

### Flyway Schema
**All tables must be in the `main` schema.** The application uses `hibernate.default_schema: main` in `application.yaml`. Use qualified table names in SQL (`main.file_storage_objects`) and create the schema in the migration if needed. The `main` schema already exists (created by earlier migrations). [Source: application.yaml:54, V01-V11 migrations]

### Flyway V12 — Complete Table DDL

```sql
CREATE TABLE IF NOT EXISTS main.file_storage_objects (
    id                   BIGINT NOT NULL,
    key                  VARCHAR(1024) NOT NULL,
    tenant_id            VARCHAR(255) NOT NULL,
    original_filename    VARCHAR(255),
    content_type         VARCHAR(255),
    size_bytes           BIGINT NOT NULL,
    checksum             VARCHAR(128),
    tags                 JSONB,
    provider             VARCHAR(50) NOT NULL,
    bucket               VARCHAR(255) NOT NULL,
    upload_confirmed_at  TIMESTAMPTZ,
    deleted_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_storage_objects_key
    ON main.file_storage_objects(key);

CREATE INDEX idx_storage_objects_tenant_id
    ON main.file_storage_objects(tenant_id);

CREATE INDEX idx_storage_objects_deleted_at
    ON main.file_storage_objects(deleted_at);

CREATE TABLE IF NOT EXISTS main.outbox_replication_jobs (
    id                   BIGINT NOT NULL,
    storage_object_id    BIGINT NOT NULL REFERENCES main.file_storage_objects(id),
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    job_type             VARCHAR(20) NOT NULL CHECK (job_type IN ('REPLICATE', 'DELETE')),
    attempt_count        INTEGER NOT NULL DEFAULT 0,
    last_attempted_at    TIMESTAMPTZ,
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT fk_replication_job_storage_object
        FOREIGN KEY (storage_object_id) REFERENCES main.file_storage_objects(id)
);

CREATE INDEX idx_replication_jobs_status
    ON main.outbox_replication_jobs(status, created_at);

CREATE INDEX idx_replication_jobs_storage_object
    ON main.outbox_replication_jobs(storage_object_id);
```

### Flyway V13 — storage_access_events DDL

```sql
CREATE TABLE IF NOT EXISTS main.storage_access_events (
    id          BIGINT NOT NULL,
    key         VARCHAR(1024) NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL,
    size_bytes  BIGINT,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_storage_access_events_tenant
    ON main.storage_access_events(tenant_id, accessed_at);
```

### JPA Entity Pattern

Entities extend `BaseEntity` (NOT `AbstractAuditingEntity` — no Envers auditing needed for infrastructure tables). Use `@Getter` and `@NoArgsConstructor` from Lombok. Class-level `@Slf4j` is required per spec. Example:

```java
@Slf4j
@Getter
@NoArgsConstructor
@Entity
@Table(name = "file_storage_objects")
public class FileStorageObject extends BaseEntity {
    @Column(name = "key", nullable = false, length = 1024)
    private String key;
    // ...
}
```

**Important:** The `tags` column is JSONB. Map it as:
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "tags", columnDefinition = "jsonb")
private Map<String, String> tags;
```

**`OutboxReplicationJob.status`** — map as a `String` column with a Java enum `ReplicationJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }` defined as an inner enum or in the `contract/` package. Use `@Enumerated(EnumType.STRING)`.

**`OutboxReplicationJob.jobType`** — map as a String (not enum) since the DB constraint already enforces valid values: `'REPLICATE'` or `'DELETE'`.

### StorageConfig Pattern

```java
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
    // No beans yet — S3Client, S3Presigner, StorageService wiring comes in Story 1.2
}
```

`StorageProperties` uses nested static inner classes for sub-groups:
```java
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {
    private String provider = "s3";
    private String bucket;
    private String endpointUrl;
    private int presignTtlSeconds = 300;
    private int uploadTtlSeconds = 600;
    private Replication replication = new Replication();
    private Quota quota = new Quota();
    private Poller poller = new Poller();
    private Retry retry = new Retry();
    private Deletion deletion = new Deletion();
    private S3 s3 = new S3();

    // Getters + Setters (or use @Data)

    public static class Replication {
        private boolean enabled = false;
        private int maxAttempts = 5;
        private long backoffInitialMs = 1000;
        private double backoffMultiplier = 2.0;
        // getters/setters
    }
    public static class Quota { private long defaultBytes = 10737418240L; }
    public static class Poller { private long fixedDelayMs = 5000; private int batchSize = 10; }
    public static class Retry { private int maxAttempts = 3; private long backoffInitialMs = 1000; private double backoffMultiplier = 2.0; }
    public static class Deletion { private int retentionDays = 30; }
    public static class S3 { private long requestTimeoutMs = 5000; private long connectionTimeoutMs = 3000; }
}
```

### StorageErrorCode — Exact Implementation Required

Must implement the existing `ErrorCode` interface at `com.softropic.skillars.infrastructure.exception.ErrorCode`:
```java
public interface ErrorCode extends Serializable {
    String getErrorCode();
}
```

Implementation:
```java
public enum StorageErrorCode implements ErrorCode {
    STORAGE_OBJECT_NOT_FOUND,
    QUOTA_EXCEEDED,
    UPLOAD_NOT_CONFIRMED,
    VALIDATION_FAILED,
    REPLICATION_FAILED,
    PROVIDER_ERROR;

    @Override
    public String getErrorCode() { return this.name(); }
}
```

### Exception Hierarchy Pattern

Look at how `ApplicationException` constructors work (`src/main/java/.../infrastructure/exception/ApplicationException.java`):
- Takes `(String msg, Map<String, Object> logContext, ErrorCode errorCode)`
- Always passes a `logContext` map for structured logging
- `getSupportId()` is auto-generated (don't override)

Each storage exception must extend `ApplicationException`:
```java
public class StorageObjectNotFoundException extends ApplicationException {
    public StorageObjectNotFoundException(String key) {
        super("Storage object not found: " + key,
              Map.of("storageKey", key),
              StorageErrorCode.STORAGE_OBJECT_NOT_FOUND);
    }
}

public class QuotaExceededException extends ApplicationException {
    public QuotaExceededException(String tenantId, long currentBytes, long requestedBytes) {
        super("Upload would exceed storage quota",
              Map.of("tenantId", tenantId, "currentBytes", currentBytes, "requestedBytes", requestedBytes),
              StorageErrorCode.QUOTA_EXCEEDED);
    }
}

public class StorageValidationException extends ApplicationException {
    public StorageValidationException(String reason) {
        super("Storage validation failed: " + reason,
              Map.of("reason", reason),
              StorageErrorCode.VALIDATION_FAILED);
    }
}

public class StorageProviderException extends ApplicationException {
    public StorageProviderException(String operation, Throwable cause) {
        super("Storage provider error during: " + operation,
              cause,
              Map.of("operation", operation),
              StorageErrorCode.PROVIDER_ERROR);
    }
}
```

### ApiAdvice Update — CRITICAL

`ApiAdvice` already exists at:
`src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`

**DO NOT create a new advice class.** ADD 4 new `@ExceptionHandler` methods to the existing class. Follow the exact pattern used for `ResourceNotFoundException`:

```java
@ExceptionHandler(StorageObjectNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public ErrorDto storageObjectNotFoundHandler(final StorageObjectNotFoundException ex) {
    return logErrorAndReturnDTO(ex, "Storage object not found", StorageErrorCode.STORAGE_OBJECT_NOT_FOUND.getErrorCode());
}

@ExceptionHandler(QuotaExceededException.class)
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public ErrorDto quotaExceededHandler(final QuotaExceededException ex) {
    return logErrorAndReturnDTO(ex, "Storage quota exceeded", StorageErrorCode.QUOTA_EXCEEDED.getErrorCode());
}

@ExceptionHandler(StorageValidationException.class)
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public ErrorDto storageValidationHandler(final StorageValidationException ex) {
    return logErrorAndReturnDTO(ex, "Storage validation failed", StorageErrorCode.VALIDATION_FAILED.getErrorCode());
}

@ExceptionHandler(StorageProviderException.class)
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public ErrorDto storageProviderHandler(final StorageProviderException ex) {
    return logErrorAndReturnDTO(ex, "Storage provider error", StorageErrorCode.PROVIDER_ERROR.getErrorCode());
}
```

Use `HttpStatus.UNPROCESSABLE_ENTITY` (422) for `StorageValidationException` and `HttpStatus.BAD_GATEWAY` (502) for `StorageProviderException`. These HTTP status codes are not yet imported — add them if the current imports only have specific statuses.

**Important**: `logErrorAndReturnDTO` is a `private` method in `ApiAdvice`. You are adding code inside the same class, so it is accessible.

### i18n Keys to Add

Add to **all three** files (`messages.properties`, `messages_en.properties`, `messages_fr.properties`):

`messages.properties` and `messages_en.properties`:
```properties
storage.objectNotFound=The requested file could not be found.
storage.quotaExceeded=Upload would exceed your storage quota.
storage.validationFailed=File validation failed: {0}
storage.uploadNotConfirmed=Upload was not confirmed within the allowed window.
storage.providerError=A storage provider error occurred. Please retry or contact support.
```

`messages_fr.properties`:
```properties
storage.objectNotFound=Le fichier demandé est introuvable.
storage.quotaExceeded=L’envoi dépasserait votre quota de stockage.
storage.validationFailed=La validation du fichier a échoué : {0}
storage.uploadNotConfirmed=L’envoi n’a pas été confirmé dans le délai imparti.
storage.providerError=Une erreur du fournisseur de stockage s’est produite. Veuillez réessayer ou contacter le support.
```

### YAML Config Addition

Add to `src/main/resources/application.yaml` (after the existing `app:` block or as a new top-level addition):
```yaml
app:
  storage:
    provider: s3
    bucket: ${APP_STORAGE_BUCKET:skillars-dev}
    endpoint-url: ${APP_STORAGE_ENDPOINT_URL:http://localhost:9000}
    presign-ttl-seconds: 300
    upload-ttl-seconds: 600
    replication:
      enabled: false
      max-attempts: 5
      backoff-initial-ms: 1000
      backoff-multiplier: 2.0
    quota:
      default-bytes: 10737418240
    poller:
      fixed-delay-ms: 5000
      batch-size: 10
    retry:
      max-attempts: 3
      backoff-initial-ms: 1000
      backoff-multiplier: 2.0
    deletion:
      retention-days: 30
    s3:
      request-timeout-ms: 5000
      connection-timeout-ms: 3000
```

**Note:** The existing `application.yaml` already has `app.environment` and `app.version`. Merge `app.storage` into the existing `app:` block rather than creating a duplicate top-level `app:` key.

### StorageResource Shell

Only a skeleton is needed — no endpoint methods. Those come in Stories 2.x and 3.x:
```java
@Slf4j
@Observed(name = "storage")
@RestController
@RequestMapping("/api/storage")
public class StorageResource {
    // Endpoint methods added in Stories 2.x and 3.x
}
```

### Project Structure Notes

**New files to create:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── api/StorageResource.java
├── config/StorageConfig.java
├── config/StorageProperties.java
├── contract/StorageErrorCode.java
├── contract/exception/StorageObjectNotFoundException.java
├── contract/exception/QuotaExceededException.java
├── contract/exception/StorageValidationException.java
├── contract/exception/StorageProviderException.java
├── repo/FileStorageObject.java
├── repo/FileStorageObjectRepository.java
├── repo/OutboxReplicationJob.java
├── repo/OutboxReplicationJobRepository.java
├── repo/StorageAccessEvent.java
└── repo/StorageAccessEventRepository.java

src/main/resources/
├── db/migration/V12__storage_schema.sql
├── db/migration/V13__storage_access_events.sql
├── i18n/messages.properties (modified)
├── i18n/messages_en.properties (modified)
└── i18n/messages_fr.properties (modified)
```

**Modified files:**
```
src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java (add 4 handlers)
src/main/resources/application.yaml (add app.storage.* keys)
```

**No new files in `service/` yet** — StorageService interface and implementations come in Story 1.2.
**No `contract/event/` file yet** — StorageObjectConfirmedEvent comes in Story 2.3.

### Cross-Story Context (Don't Overstep)

Story 1.1 establishes the **skeleton only**. Do NOT implement:
- `StorageService` interface (Story 1.2)
- `S3StorageService` / `S3Client` beans (Story 1.2)
- `StorageKeyGenerator` (Story 1.2)
- `ValidationChain` (Story 2.1)
- REST endpoint methods (Stories 2.2, 2.3, 3.1, 3.2)
- `StorageObjectConfirmedEvent` (Story 2.3)
- `BaseStorageIT` / MinIO test harness (Story 1.3)

### References

- [Source: architecture.md#Package Layout] — exact package hierarchy
- [Source: architecture.md#Data Architecture] — `file_storage_objects` and `outbox_replication_jobs` column definitions
- [Source: architecture.md#Naming Patterns] — table names, class names, YAML key namespace
- [Source: architecture.md#Exception Pattern] — exception base class and error code pattern
- [Source: architecture.md#Format Patterns] — error envelope shape using `ErrorDto`
- [Source: epics.md#Story 1.1] — acceptance criteria BDD specs
- [Source: BaseEntity.java] — `@Tsid Long id` pattern
- [Source: ApiAdvice.java] — existing exception handler patterns to replicate
- [Source: ApplicationException.java] — constructor signatures for exception subclasses
- [Source: ErrorCode.java] — interface contract for `StorageErrorCode`
- [Source: SecurityConstants.java] — `HAS_ANY_ROLE`, `HAS_ADMIN_ROLE` constants for future `@PreAuthorize` use
- [Source: application.yaml] — existing `app:` block to merge into
- [Source: messages.properties, messages_en.properties, messages_fr.properties] — i18n file structure

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None — implementation proceeded without blockers.

### Completion Notes List

- All 9 tasks completed in a single session (2026-05-25).
- Package skeleton established via real implementation files plus `package-info.java` for `service/` and `contract/event/` (no service or event classes yet, per cross-story scope).
- `StorageProperties` uses nested static inner classes with `@Getter`/`@Setter` for all 6 sub-groups, matching all 15 YAML keys in AC-3.
- Flyway V12 and V13 SQL follows the exact DDL from Dev Notes; tables in `main` schema with BIGINT PKs (TSID-compatible).
- JPA entities extend `BaseEntity`, use `@SuperBuilder`/`@NoArgsConstructor`, `@Slf4j`, `@Getter`. `FileStorageObject.tags` mapped as JSONB via `@JdbcTypeCode(SqlTypes.JSON)`. `OutboxReplicationJob.status` mapped as `@Enumerated(EnumType.STRING)`.
- Exception hierarchy follows `ApplicationException` constructor signatures exactly; `StorageProviderException` uses the `(msg, cause, logContext, errorCode)` overload.
- 4 handlers added to existing `ApiAdvice` (NOT a new advice class): 404, 429, 422, 502 status codes.
- i18n keys added to all 3 property files with EN and FR translations.
- Clean `mvn clean compile` — BUILD SUCCESS. 2 existing unit tests pass; no regressions.

### File List

**New files:**
- `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/StorageErrorCode.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/event/package-info.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/exception/QuotaExceededException.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/exception/StorageObjectNotFoundException.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/exception/StorageProviderException.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/exception/StorageValidationException.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObject.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJob.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepository.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/StorageAccessEvent.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/StorageAccessEventRepository.java`
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/package-info.java`
- `src/main/resources/db/migration/V12__storage_schema.sql`
- `src/main/resources/db/migration/V13__storage_access_events.sql`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
- `src/main/resources/application.yaml`
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_fr.properties`

## Change Log

- 2026-05-25: Story 1.1 implemented — storage module scaffold, Flyway V12/V13, JPA entities, repositories, exception hierarchy, ApiAdvice handlers, i18n keys, YAML configuration. Status → review.
