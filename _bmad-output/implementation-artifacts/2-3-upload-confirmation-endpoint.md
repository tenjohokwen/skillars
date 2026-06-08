# Story 2.3: Upload Confirmation Endpoint

Status: review

## Story

As a client application,
I want to confirm that my direct-to-S3 upload succeeded so the system registers the file and triggers backup replication,
so that the file is tracked, quotas are accurate, and downstream consumers are notified.

## Acceptance Criteria

1. **headObject runs OUTSIDE @Transactional**: Given a client has PUT a file to S3 using the URL from Story 2.2, when `POST /api/storage/confirm/{key}` is called, then `StorageSigningService` calls `s3Client.headObject(key)` before any `@Transactional` boundary opens to verify the object exists in S3.

2. **Metadata validation rejects mismatches**: Given a headObject succeeds, when actual `ContentType` or `ContentLength` from headObject differ from the declared values in `ConfirmUploadRequest`, then `StorageValidationException` is thrown (HTTP 422). ETag is validated as non-null/non-empty (proves upload completed) but is NOT compared to the SHA-256 checksum field (different hash algorithms).

3. **Atomic DB inserts in a single transaction**: Given validation passes, when the `@Transactional` block executes, then a `file_storage_objects` record is inserted with all metadata (originalFilename, contentType from headObject, sizeBytes from headObject, checksum from request, tenantId, provider, bucket, uploadConfirmedAt, tags), AND within the same transaction an `outbox_replication_jobs` record with `status = PENDING` and `job_type = 'REPLICATE'` is inserted referencing the new FSO.

4. **Event published after transaction commit**: Given the transaction committed successfully, when the method returns, then `ApplicationEventPublisher.publishEvent(new StorageObjectConfirmedEvent(this, fso))` is called with the persisted `FileStorageObject`.

5. **Key not found returns 404**: Given the key does not exist in S3 (headObject throws `NoSuchKeyException`), when `POST /api/storage/confirm/{key}` is called, then `StorageObjectNotFoundException` is thrown (HTTP 404).

6. **Idempotent confirm returns existing record**: Given a `file_storage_objects` record already exists for the key, when the confirm endpoint is called again, then no new records are inserted and the existing `ConfirmUploadResponse` is returned.

7. **Jakarta validation rejects incomplete requests**: Given `ConfirmUploadRequest` is missing any required field (`contentType`, `fileSizeBytes`, `tenantId`), when the confirm endpoint is called, then HTTP 400 is returned before reaching service logic.

8. **Endpoint is secured and observed**: When any request reaches `StorageResource.confirmUpload`, then `@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)` guards the method and `@Observed(name = "storage.confirm.upload")` is on the method.

9. **ConfirmUploadResponse contains required fields**: Given a successful confirmation, then the response contains `id` (Long TSID), `key`, `sizeBytes` (from headObject actual), `contentType` (from headObject actual), `checksum` (from request, prefixed `sha256:` if non-null), and `uploadedAt` (ISO 8601 — the `uploadConfirmedAt` value).

10. **Integration test covers all scenarios**: Given a live MinIO + PostgreSQL container (via `BaseStorageIT`), the integration test covers: successful confirm after real PUT, 404 on missing key, 422 on contentType mismatch, 422 on fileSizeBytes mismatch, and idempotent re-confirm.

## Tasks / Subtasks

- [x] Task 1: Create `ConfirmUploadRequest` record in `contract/` (AC: 2, 7)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/ConfirmUploadRequest.java`
  - [x] Implement as Java `record` (per project rule — all HTTP request DTOs are records)
  - [x] Fields with Jakarta validation:
    - `@NotBlank String contentType` — declared MIME type (for validation against actual)
    - `@Positive long fileSizeBytes` — declared file size in bytes (for validation against actual)
    - `@NotBlank String tenantId` — tenant identifier (for populating `file_storage_objects`)
    - `String originalFilename` — optional; if null, derive from the key (last path segment)
    - `String checksum` — optional raw SHA-256 hex (64 lowercase hex chars); no format annotation needed
    - `Map<String, String> tags` — optional JSONB metadata; may be null

- [x] Task 2: Create `ConfirmUploadResponse` record in `contract/` (AC: 9)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/ConfirmUploadResponse.java`
  - [x] Fields:
    - `Long id` — the TSID-based `FileStorageObject.id`
    - `String key` — the storage key
    - `long sizeBytes` — actual size from headObject (not declared)
    - `String contentType` — actual contentType from headObject (not declared)
    - `String checksum` — `"sha256:" + request.checksum()` if non-null, else `null`
    - `Instant uploadedAt` — the `uploadConfirmedAt` timestamp

- [x] Task 3: Create `StorageObjectConfirmedEvent` in `contract/event/` (AC: 4)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/event/StorageObjectConfirmedEvent.java`
  - [x] Extend `org.springframework.context.ApplicationEvent`
  - [x] Hold a `FileStorageObject storageObject` field with a getter
  - [x] Constructor: `public StorageObjectConfirmedEvent(Object source, FileStorageObject storageObject)`
  - [x] Package-info.java for this package already exists; the event class is listed there

- [x] Task 4: Add `findByKey` to `FileStorageObjectRepository` (AC: 6)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java`
  - [x] Add: `Optional<FileStorageObject> findByKey(String key);`
  - [x] Spring Data derives the query automatically — no `@Query` needed
  - [x] Add `java.util.Optional` import

- [x] Task 5: Add `TransactionTemplate` bean to `StorageConfig` (AC: 3, 4)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`
  - [x] Add bean: `@Bean public TransactionTemplate storageTransactionTemplate(PlatformTransactionManager tm) { return new TransactionTemplate(tm); }`
  - [x] Spring Boot auto-configures `PlatformTransactionManager`; inject it directly
  - [x] Import `org.springframework.transaction.PlatformTransactionManager` and `org.springframework.transaction.support.TransactionTemplate`

- [x] Task 6: Implement `confirmUpload` in `StorageSigningService` (AC: 1, 2, 3, 4, 5, 6)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java`
  - [x] Add constructor fields (Lombok `@RequiredArgsConstructor` generates the constructor):
    - `private final S3Client s3Client;` (already a Spring bean from `StorageConfig`)
    - `private final OutboxReplicationJobRepository outboxReplicationJobRepository;`
    - `private final ApplicationEventPublisher applicationEventPublisher;`
    - `private final TransactionTemplate transactionTemplate;` (the `storageTransactionTemplate` bean)
  - [x] Implement `public ConfirmUploadResponse confirmUpload(String key, ConfirmUploadRequest request)`:
    ```
    1. Idempotency: fileStorageObjectRepository.findByKey(key) → if present → return toResponse(existing)
    2. headObject OUTSIDE transaction:
       try { HeadObjectResponse head = s3Client.headObject(r -> r.bucket(properties.getBucket()).key(key)); }
       catch (NoSuchKeyException e) { throw new StorageObjectNotFoundException(key, e); }
    3. Validate contentType: if (!request.contentType().equals(head.contentType()))
           throw new StorageValidationException("contentType mismatch: declared=" + request.contentType() + ", actual=" + head.contentType())
    4. Validate contentLength: if (request.fileSizeBytes() != head.contentLength())
           throw new StorageValidationException("fileSizeBytes mismatch: declared=" + request.fileSizeBytes() + ", actual=" + head.contentLength())
    5. Validate ETag present: if (head.eTag() == null || head.eTag().isBlank())
           throw new StorageValidationException("upload incomplete: ETag absent for key " + key)
    6. @Transactional insert via transactionTemplate.execute(status -> { ... }):
       a. Build FileStorageObject with builder (all fields)
       b. save FileStorageObject
       c. Build OutboxReplicationJob (jobType=REPLICATE, status=PENDING, attemptCount=0)
       d. save OutboxReplicationJob
       e. return saved FileStorageObject
    7. Publish event: applicationEventPublisher.publishEvent(new StorageObjectConfirmedEvent(this, saved))
    8. return toResponse(saved)
    ```
  - [x] Add private helper `toConfirmUploadResponse(FileStorageObject fso)` returning `ConfirmUploadResponse`
  - [x] `checksum` in response: `fso.getChecksum() != null ? "sha256:" + fso.getChecksum() : null`
  - [x] Required imports:
    - `software.amazon.awssdk.services.s3.S3Client`
    - `software.amazon.awssdk.services.s3.model.HeadObjectResponse`
    - `software.amazon.awssdk.services.s3.model.NoSuchKeyException`
    - `com.softropic.skillars.infrastructure.storage.contract.ConfirmUploadRequest`
    - `com.softropic.skillars.infrastructure.storage.contract.ConfirmUploadResponse`
    - `com.softropic.skillars.infrastructure.storage.contract.event.StorageObjectConfirmedEvent`
    - `com.softropic.skillars.infrastructure.storage.contract.exception.StorageObjectNotFoundException`
    - `com.softropic.skillars.infrastructure.storage.contract.exception.StorageValidationException`
    - `com.softropic.skillars.infrastructure.storage.repo.OutboxReplicationJob`
    - `com.softropic.skillars.infrastructure.storage.repo.OutboxReplicationJobRepository`
    - `org.springframework.context.ApplicationEventPublisher`
    - `org.springframework.transaction.support.TransactionTemplate`
    - `java.time.Instant`
    - `java.util.Optional`

- [x] Task 7: Add `confirmUpload` endpoint to `StorageResource` (AC: 7, 8)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java`
  - [x] Add endpoint — **CRITICAL: use `/**` wildcard** because the storage key contains forward slashes (e.g., `documents/42/2026/05/uuid.pdf`):
    ```java
    @PostMapping("/confirm/**")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    @Observed(name = "storage.confirm.upload")
    public ResponseEntity<ConfirmUploadResponse> confirmUpload(
        HttpServletRequest request,
        @RequestBody @Valid ConfirmUploadRequest body) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = fullPath.substring("/api/storage/confirm/".length());
        return ResponseEntity.ok(storageSigningService.confirmUpload(key, body));
    }
    ```
  - [x] Imports to add:
    - `jakarta.servlet.http.HttpServletRequest`
    - `org.springframework.web.servlet.HandlerMapping`
    - `com.softropic.skillars.infrastructure.storage.contract.ConfirmUploadRequest`
    - `com.softropic.skillars.infrastructure.storage.contract.ConfirmUploadResponse`

- [x] Task 8: Write `StorageConfirmUploadIT` integration test (AC: 10)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageConfirmUploadIT.java`
  - [x] Annotate to extend `BaseStorageIT`
  - [x] Inject `@Autowired StorageSigningService storageSigningService`
  - [x] Inject `@Autowired FileStorageObjectRepository fileStorageObjectRepository`
  - [x] Inject `@Autowired OutboxReplicationJobRepository outboxReplicationJobRepository`
  - [x] Inject `@Autowired RestTemplate restTemplate`
  - [x] `@BeforeEach`: call `outboxReplicationJobRepository.deleteAll()` then `fileStorageObjectRepository.deleteAll()` (in that order — outbox has FK to fso)
  - [x] Test cases:
    - **confirmUpload_afterRealPut_savesRecordAndReturnsResponse**: Call `signUpload`, PUT file to `uploadUrl`, call `confirmUpload`, assert FSO saved in DB (findByKey), assert outbox job saved (findAll → 1 REPLICATE PENDING job), assert response fields non-null
    - **confirmUpload_missingKey_throws404**: Call `confirmUpload` with a non-existent key. Assert `StorageObjectNotFoundException` is thrown
    - **confirmUpload_contentTypeMismatch_throws422**: Call `signUpload` with `contentType=application/pdf`, PUT file to MinIO, call `confirmUpload` with `contentType=image/png`. Assert `StorageValidationException` is thrown
    - **confirmUpload_fileSizeMismatch_throws422**: Call `signUpload` with `fileSizeBytes=3`, PUT 3 bytes to MinIO, call `confirmUpload` with `fileSizeBytes=99`. Assert `StorageValidationException` is thrown
    - **confirmUpload_idempotent_returnsExistingRecord**: Call the full flow (sign → PUT → confirm), then call confirm again with the same key. Assert only one `file_storage_objects` record exists for that key; second call returns same response
  - [x] Use `AssertJ` (`assertThat`, `assertThatThrownBy`) for all assertions
  - [x] Build `ConfirmUploadRequest` with matching values when testing happy path; use mismatched values for validation tests

- [x] Task 9: Verify build passes (AC: all)
  - [x] Run `mvn test -Dtest=StorageConfirmUploadIT` from project root and confirm BUILD SUCCESS
  - [x] Run regression: `mvn test -Dtest=ValidationChainTest,StorageKeyGeneratorTest,S3StorageServiceIT,StorageSigningServiceIT,StorageConfirmUploadIT`

## Dev Notes

### Critical: Path Variable With Slashes

The storage key `documents/42/2026/05/uuid.pdf` contains forward slashes. Using `@PostMapping("/confirm/{key}")` with `@PathVariable String key` will NOT work — Spring captures only up to the first slash. The correct approach:

```java
@PostMapping("/confirm/**")
public ResponseEntity<ConfirmUploadResponse> confirmUpload(
    HttpServletRequest request,
    @RequestBody @Valid ConfirmUploadRequest body) {
    String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
    String key = fullPath.substring("/api/storage/confirm/".length());
    ...
}
```

`HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE` is the standard Spring constant for extracting the matched path. Import `org.springframework.web.servlet.HandlerMapping`.

### Critical: Transaction Boundary (Architecture Rule)

S3 `headObject` MUST be called OUTSIDE `@Transactional`. The `@Transactional` block wraps ONLY the DB writes (both `fileStorageObjectRepository.save` and `outboxReplicationJobRepository.save` atomically). The `ApplicationEventPublisher.publishEvent` call is OUTSIDE the transaction (called after `transactionTemplate.execute` returns). The correct sequence:

```
1. fileStorageObjectRepository.findByKey(key)   ← idempotency (Spring Data read-only auto-tx)
2. s3Client.headObject(key)                      ← OUTSIDE @Transactional
3. validate actuals vs declared                  ← OUTSIDE @Transactional
4. transactionTemplate.execute(status -> {        ← @Transactional BEGINS
       fso = fileStorageObjectRepository.save(...)
       outboxReplicationJobRepository.save(...)
       return fso;
   });                                            ← @Transactional COMMITS
5. applicationEventPublisher.publishEvent(...)   ← AFTER COMMIT
6. return ConfirmUploadResponse                  ← AFTER COMMIT
```

Use `transactionTemplate.execute(TransactionCallback)` — NOT a `@Transactional` annotation on `confirmUpload` or a nested `@Transactional` method (self-invocation is not intercepted by Spring AOP proxy).

### Why TransactionTemplate (Not @Transactional Self-Call)

If `@Transactional` is on a private/protected method and called from within `StorageSigningService`, Spring's proxy cannot intercept it — the annotation is silently ignored and no transaction is started. This is the Spring self-invocation limitation. `TransactionTemplate` bypasses this by invoking the transaction manager directly, giving explicit control over the transaction boundary. This is the correct pattern when the @Transactional scope must be inside a larger non-transactional method.

`TransactionTemplate` is registered as a Spring bean in `StorageConfig` (Task 5). Inject it via constructor into `StorageSigningService`.

### FileStorageObject Builder — Populating All Fields

`FileStorageObject` extends `BaseEntity` which uses `@Tsid` for `id` — do NOT set `id` manually. Use the `@SuperBuilder` pattern:

```java
Instant confirmedAt = Instant.now();
FileStorageObject fso = FileStorageObject.builder()
    .key(key)
    .tenantId(request.tenantId())
    .originalFilename(resolveOriginalFilename(request, key))
    .contentType(head.contentType())       // actual from headObject
    .sizeBytes(head.contentLength())       // actual from headObject
    .checksum(request.checksum())          // raw sha256 hex, stored as-is
    .tags(request.tags())
    .provider(properties.getProvider())   // e.g., "s3"
    .bucket(properties.getBucket())
    .uploadConfirmedAt(confirmedAt)
    .build();
```

`resolveOriginalFilename`: if `request.originalFilename() != null`, use it; otherwise extract the filename from the key (everything after the last `/`).

### OutboxReplicationJob Builder

```java
OutboxReplicationJob job = OutboxReplicationJob.builder()
    .storageObject(fso)
    .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
    .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
    .attemptCount(0)
    .build();
```

`@PrePersist` in `OutboxReplicationJob` sets `createdAt` and defaults `status` to PENDING if null — but explicitly setting status is fine and makes intent clear.

### S3Client Is Already a Spring Bean

`StorageConfig.s3Client()` registers `S3Client` as `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)`. Simply add `private final S3Client s3Client;` to `StorageSigningService` — `@RequiredArgsConstructor` picks it up automatically.

### IdempotencyCheck — findByKey Returns Deleted Records Too

For Story 2.3, `findByKey` does NOT filter on `deletedAt`. If a file was confirmed then soft-deleted, a re-confirm returns the existing FSO response (the confirm endpoint is about registering the upload, not about current availability). This is intentional — soft-delete status is orthogonal to upload confirmation status.

### contentType Comparison — S3 Normalizes MIME Types

When a client PUTs to MinIO with `Content-Type: application/pdf`, S3/MinIO stores it exactly as declared. `headObject.contentType()` returns the stored value. However, some S3-compatible implementations may normalize MIME types (e.g., stripping parameters). The strict equality check `request.contentType().equals(head.contentType())` is correct per the spec. If tests flake on this, check if MinIO strips encoding parameters.

### ETag Format Note

MinIO returns ETag as the MD5 hex digest of the uploaded bytes (wrapped in double quotes, e.g., `"d41d8cd98f00b204e9800998ecf8427e"`). The declared `checksum` in `ConfirmUploadRequest` is a raw SHA-256 hex string (no quotes, different algorithm). These cannot be directly compared — do NOT attempt ETag-to-checksum comparison. Only validate ETag is present (non-null, non-blank) to confirm the upload completed.

### ConfirmUploadResponse — checksum Field Formatting

The architecture specifies `"checksum": "sha256:..."` format. The raw hex from `SignUploadRequest` (64 chars) is stored in `file_storage_objects.checksum` as-is. At response time, prefix it:

```java
private String formatChecksum(String rawChecksum) {
    return rawChecksum != null && !rawChecksum.isBlank() ? "sha256:" + rawChecksum : null;
}
```

### StorageObjectConfirmedEvent — After-Commit Publishing

Story 2.3 only publishes the event; it does NOT implement any listener. Event publishing inside `applicationEventPublisher.publishEvent(...)` is synchronous by default — if a listener later uses `@TransactionalEventListener(phase = AFTER_COMMIT)`, it fires after commit automatically. Publishing OUTSIDE the transaction (as required) ensures even synchronous listeners don't accidentally run inside the transaction.

### Integration Test — Test Data Setup Order

`OutboxReplicationJob` has a FK referencing `FileStorageObject`. Always delete outbox jobs before FSO records in `@BeforeEach`:

```java
@BeforeEach
void setUp() {
    outboxReplicationJobRepository.deleteAll();
    fileStorageObjectRepository.deleteAll();
}
```

Reversing the order causes FK constraint violation.

### Integration Test — Building ConfirmUploadRequest

After calling `signUpload` (which returns `SignUploadResponse` with `key`, `uploadUrl`, `expiresAt`), build the confirm request with MATCHING declared values:

```java
SignUploadResponse signResponse = storageSigningService.signUpload(signRequest);

// PUT file to MinIO using the presigned URL
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_PDF);
byte[] content = new byte[]{1, 2, 3};
restTemplate.exchange(URI.create(signResponse.uploadUrl()), HttpMethod.PUT,
    new HttpEntity<>(content, headers), Void.class);

// Confirm with MATCHING values
ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
    "application/pdf",  // must match Content-Type sent in PUT
    content.length,     // must match actual bytes uploaded
    "tenant-1",
    null,               // originalFilename optional
    null,               // checksum optional
    null                // tags optional
);

ConfirmUploadResponse confirmResponse = storageSigningService.confirmUpload(signResponse.key(), confirmRequest);
```

### Previous Story Learnings (Story 2.2)

- **`@RequiredArgsConstructor` pattern**: `StorageSigningService` uses Lombok `@RequiredArgsConstructor` to generate its constructor. Adding new `final` fields automatically includes them in the constructor. The field declaration order determines constructor parameter order.
- **Transaction rule**: Story 2.2 confirmed: NO `@Transactional` on `signUpload`. Story 2.3 continues this — `confirmUpload` itself is NOT annotated `@Transactional`; the transaction is scoped to the `transactionTemplate.execute()` lambda only.
- **MinIO path-style URLs**: The `s3Presigner` is configured with `pathStyleAccessEnabled(true)` for MinIO. `s3Client` (used here for headObject) uses the same configuration. No additional configuration needed for headObject.
- **BaseStorageIT provides RestTemplate**: `BaseStorageIT.StorageTestConfig` registers a `@Primary RestTemplate` bean. Inject with `@Autowired RestTemplate restTemplate` in the test class — no additional setup needed.
- **Build is clean**: All 29 tests pass as of Story 2.2. Integration test regression suite must continue passing.

### What Is NOT in This Story

- `GET /api/storage/sign/download/{key}` endpoint (Story 3.1)
- `storage_access_events` insertion (Story 3.1)
- `DELETE /api/storage/{key}` endpoint (Story 3.2)
- `DeletionSchedulerService` (Story 3.2)
- `OutboxPollerScheduler` — the REPLICATE outbox job inserted here is NOT processed until Epic 4 / Story 4.2
- `@TransactionalEventListener` implementation (downstream consumers, future stories)
- Per-tenant quota updates — quotas are based on confirmed `file_storage_objects` records; the confirmed file now counts toward quota for future sign-upload requests

### Project Structure Notes

New and modified files:

```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── api/
│   └── StorageResource.java                ← MODIFY: add confirmUpload endpoint
├── config/
│   └── StorageConfig.java                  ← MODIFY: add TransactionTemplate bean
├── contract/
│   ├── ConfirmUploadRequest.java           ← NEW (record with Jakarta validation)
│   ├── ConfirmUploadResponse.java          ← NEW (record: id, key, sizeBytes, contentType, checksum, uploadedAt)
│   └── event/
│       └── StorageObjectConfirmedEvent.java ← NEW (extends ApplicationEvent)
├── repo/
│   └── FileStorageObjectRepository.java    ← MODIFY: add findByKey(String key)
└── service/
    └── StorageSigningService.java          ← MODIFY: add confirmUpload + new injected fields

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageConfirmUploadIT.java             ← NEW (extends BaseStorageIT)
```

### References

- [Source: epics.md#Story 2.3] — Full BDD acceptance criteria, ConfirmUploadResponse shape
- [Source: architecture.md#Transaction Boundary] — S3 outside @Transactional; DB writes inside; publishEvent after commit
- [Source: architecture.md#Format Patterns] — ConfirmUploadResponse: `{ id, key, sizeBytes, contentType, checksum, uploadedAt }`
- [Source: architecture.md#REST Endpoint Naming] — `POST /api/storage/confirm/{key}`
- [Source: architecture.md#Complete Project Directory Structure] — package layout
- [Source: project-context.md#Language-Specific Rules] — All HTTP DTOs are Java `record` types
- [Source: project-context.md#Framework-Specific Rules] — `@PreAuthorize` on every method; `@Observed` on resources; `@Transactional` only for DB writes
- [Source: project-context.md#Testing Rules] — `@SpringBootTest + @Testcontainers`; no mock DB; AssertJ; Instancio
- [Source: StorageConfig.java] — `S3Client` bean at lines 46-61; `S3Presigner` bean at 78-89
- [Source: StorageSigningService.java] — existing signUpload method; @RequiredArgsConstructor pattern; ValidationChain, FileStorageObjectRepository, StorageKeyGenerator, S3Presigner, StorageProperties injected
- [Source: FileStorageObject.java] — @SuperBuilder; all column fields; @PrePersist for createdAt
- [Source: OutboxReplicationJob.java] — @SuperBuilder; ReplicationJobStatus enum; ReplicationJobType enum; @ManyToOne to FileStorageObject; @PrePersist for createdAt + default status
- [Source: FileStorageObjectRepository.java] — existing sumSizeBytesByTenantId JPQL query pattern
- [Source: ApiAdvice.java:370-392] — all four storage exceptions already registered (StorageObjectNotFoundException→404, QuotaExceededException→429, StorageValidationException→422, StorageProviderException→502). No new handlers needed for this story.
- [Source: SecurityConstants.java] — HAS_ANY_ROLE constant
- [Source: BaseStorageIT.java] — test base class; RestTemplate bean
- [Source: StorageSigningServiceIT.java] — test patterns: Instancio for entities, assertThatThrownBy, BeforeEach deleteAll
- [Source: 2-2-pre-signed-upload-url-endpoint.md#Dev Agent Record] — 3 pre-existing bugs fixed (credentials, key sanitization, presigner path-style); build clean with 29 tests passing

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

No debug issues encountered. Clean implementation on first pass.

### Completion Notes List

- Created ConfirmUploadRequest record with @NotBlank/@Positive Jakarta validation on required fields
- Created ConfirmUploadResponse record with all required fields (id, key, sizeBytes, contentType, checksum, uploadedAt)
- Created StorageObjectConfirmedEvent extending ApplicationEvent with FileStorageObject payload
- Added findByKey(String) to FileStorageObjectRepository — Spring Data derives query automatically
- Added storageTransactionTemplate bean to StorageConfig; TransactionTemplate injected (not @Transactional) to enforce explicit transaction boundary inside a non-transactional confirmUpload method
- Implemented confirmUpload following exact transaction boundary spec: idempotency check → headObject outside tx → validate → transactionTemplate.execute (fso save + outbox save) → publishEvent after commit → return response
- Added /confirm/** wildcard mapping in StorageResource to handle keys containing forward slashes; key extracted via HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
- StorageConfirmUploadIT: 5 integration tests all pass against live MinIO + PostgreSQL containers — happy path, 404, contentType mismatch 422, fileSizeBytes mismatch 422, and idempotent re-confirm
- Full regression: 34 tests, 0 failures (29 pre-existing + 5 new)

### File List

- src/main/java/com/softropic/skillars/infrastructure/storage/contract/ConfirmUploadRequest.java (NEW)
- src/main/java/com/softropic/skillars/infrastructure/storage/contract/ConfirmUploadResponse.java (NEW)
- src/main/java/com/softropic/skillars/infrastructure/storage/contract/event/StorageObjectConfirmedEvent.java (NEW)
- src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java (MODIFIED)
- src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageConfirmUploadIT.java (NEW)

## Change Log

- 2026-05-26: Story 2.3 implemented — POST /api/storage/confirm/** endpoint with S3 headObject validation, atomic DB inserts (FSO + outbox job), event publishing after commit, idempotent re-confirm. 5 new integration tests added, all 34 tests pass.
